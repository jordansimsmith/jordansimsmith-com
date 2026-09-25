import argparse
import json
import os
import shutil
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import boto3

PLAN_DIR = Path("/tmp")


def parse_args():
    parser = argparse.ArgumentParser(
        description="Apply terraform across manifest-defined infra directories"
    )
    parser.add_argument(
        "workspace_file",
        type=Path,
        help="Path to a file in the workspace root (kept for Bazel run compatibility).",
    )
    parser.add_argument("manifest", type=Path, help="Path to terraform manifest file")
    parser.add_argument(
        "--path",
        action="append",
        dest="paths",
        help="Limit to a manifest terraform root path (e.g. auth_api/infra); repeatable.",
    )
    return parser.parse_args()


def load_terraform_roots(workspace_root, manifest_path, paths):
    manifest = json.loads(manifest_path.read_text())
    terraform_roots = []
    for entry in manifest["terraform_roots"]:
        relative_path = Path(entry["path"])
        terraform_roots.append(
            {
                "relative_path": relative_path,
                "infra_dir": workspace_root / relative_path,
                "name": relative_path.parts[0],
                "artifacts": entry["artifacts"],
                "images": entry.get("images", {}),
            }
        )

    if paths:
        known_paths = {str(root["relative_path"]) for root in terraform_roots}
        unknown_paths = set(paths) - known_paths
        if unknown_paths:
            raise ValueError(
                f"Paths not found in manifest: {sorted(unknown_paths)}. "
                f"Known paths: {sorted(known_paths)}"
            )
        terraform_roots = [
            root for root in terraform_roots if str(root["relative_path"]) in paths
        ]

    return terraform_roots


def _normalise_label(label):
    if label.startswith("@@//"):
        return label[2:]
    if label.startswith("@//"):
        return label[1:]
    return label


def resolve_bazel_outputs(workspace_root, labels):
    labels = sorted(set(labels))
    if not labels:
        return {}

    print(f"Building and resolving {len(labels)} Bazel outputs...")
    subprocess.run(
        ["bazel", "build", *labels],
        cwd=workspace_root,
        check=True,
    )

    cquery_expression = (
        '"%s\\t%s" % '
        '(str(target.label), ",".join([f.path for f in target.files.to_list()]))'
    )
    result = subprocess.run(
        [
            "bazel",
            "cquery",
            "--output=starlark",
            f"--starlark:expr={cquery_expression}",
            f"set({' '.join(labels)})",
        ],
        cwd=workspace_root,
        capture_output=True,
        text=True,
        check=True,
    )

    outputs = {}
    for line in result.stdout.splitlines():
        if "\t" not in line:
            continue

        label, file_paths_csv = line.strip().split("\t", 1)
        file_paths = [path for path in file_paths_csv.split(",") if path]
        if len(file_paths) != 1:
            raise RuntimeError(
                f"Expected exactly one output path for `{label}`, "
                f"found {len(file_paths)}: {file_paths}"
            )
        outputs[_normalise_label(label)] = workspace_root / file_paths[0]

    missing_labels = {_normalise_label(label) for label in labels} - outputs.keys()
    if missing_labels:
        raise RuntimeError(
            f"Bazel did not resolve output paths for: {sorted(missing_labels)}"
        )
    return outputs


def resolve_required_outputs(workspace_root, terraform_roots):
    labels = [
        label
        for terraform_root in terraform_roots
        for label in terraform_root["artifacts"].values()
    ]
    labels.extend(
        image["digest"]
        for terraform_root in terraform_roots
        for image in terraform_root["images"].values()
    )
    return resolve_bazel_outputs(workspace_root, labels)


def ensure_ecr_repository(ecr_client, image):
    repository_name = image["repository"]
    try:
        response = ecr_client.describe_repositories(repositoryNames=[repository_name])
        return response["repositories"][0]
    except ecr_client.exceptions.RepositoryNotFoundException:
        response = ecr_client.create_repository(
            repositoryName=repository_name,
            imageTagMutability="IMMUTABLE",
            imageScanningConfiguration={"scanOnPush": True},
        )
        return response["repository"]


def prepare_image_inputs(terraform_roots, outputs):
    image_inputs = {}
    ecr_clients = {}
    for terraform_root in terraform_roots:
        root_inputs = {}
        for image_name, image in terraform_root["images"].items():
            digest_path = outputs[_normalise_label(image["digest"])]
            digest = digest_path.read_text().strip()
            if not digest.startswith("sha256:"):
                raise RuntimeError(
                    f"Expected an OCI sha256 digest in `{digest_path}`, found `{digest}`"
                )

            if image["region"] not in ecr_clients:
                ecr_clients[image["region"]] = boto3.client(
                    "ecr", region_name=image["region"]
                )
            repository = ensure_ecr_repository(ecr_clients[image["region"]], image)
            root_inputs[image_name] = {
                "repository": image["repository"],
                "uri": f"{repository['repositoryUri']}@{digest}",
            }
        if root_inputs:
            image_inputs[terraform_root["infra_dir"]] = root_inputs
    return image_inputs


def _ecr_docker_config(region):
    ecr = boto3.client("ecr", region_name=region)
    authorization = ecr.get_authorization_token()["authorizationData"][0]
    registry = authorization["proxyEndpoint"].removeprefix("https://")
    return {"auths": {registry: {"auth": authorization["authorizationToken"]}}}


def push_images(workspace_root, terraform_roots, image_inputs):
    for terraform_root in terraform_roots:
        root_inputs = image_inputs.get(terraform_root["infra_dir"], {})
        for image_name, image in terraform_root["images"].items():
            image_uri = root_inputs[image_name]["uri"]
            with tempfile.TemporaryDirectory(prefix="ecr-docker-config-") as config_dir:
                config_path = Path(config_dir) / "config.json"
                config_path.write_text(
                    json.dumps(_ecr_docker_config(image["region"])) + "\n"
                )
                environment = os.environ.copy()
                environment["DOCKER_CONFIG"] = config_dir
                subprocess.run(
                    [
                        "bazel",
                        "run",
                        image["push"],
                        "--",
                        "--repository",
                        image_uri.split("@", 1)[0],
                    ],
                    cwd=workspace_root,
                    env=environment,
                    check=True,
                )


def write_tfvars(terraform_roots, outputs, image_inputs):
    tfvars_dir = Path(tempfile.mkdtemp(prefix="terraform-artifacts-"))
    tfvars_by_root = {}
    for terraform_root in terraform_roots:
        artifacts = {
            artifact_key: str(outputs[_normalise_label(label)])
            for artifact_key, label in terraform_root["artifacts"].items()
        }
        values = {"artifacts": artifacts}
        if terraform_root["images"]:
            values["images"] = image_inputs[terraform_root["infra_dir"]]

        tfvars_path = (
            tfvars_dir
            / f"{'_'.join(terraform_root['relative_path'].parts)}.tfvars.json"
        )
        tfvars_path.write_text(json.dumps(values, indent=2) + "\n")
        tfvars_by_root[terraform_root["infra_dir"]] = tfvars_path
    return tfvars_dir, tfvars_by_root


def plan_path(terraform_root):
    return PLAN_DIR / f"{terraform_root['name']}.tfplan"


def run_plan(terraform_root, tfvars_by_root):
    try:
        subprocess.run(
            ["terraform", "init", "-input=false"],
            cwd=terraform_root["infra_dir"],
            capture_output=True,
            text=True,
            check=True,
        )
    except subprocess.CalledProcessError as exc:
        return terraform_root, 1, exc.stdout, exc.stderr

    result = subprocess.run(
        [
            "terraform",
            "plan",
            f"-out={plan_path(terraform_root)}",
            "-detailed-exitcode",
            "-input=false",
            f"-var-file={tfvars_by_root[terraform_root['infra_dir']]}",
        ],
        cwd=terraform_root["infra_dir"],
        capture_output=True,
        text=True,
    )
    return terraform_root, result.returncode, result.stdout, result.stderr


def plan_all(terraform_roots, tfvars_by_root):
    print("Planning all directories...")
    with_changes = []
    with ThreadPoolExecutor() as executor:
        futures = {
            executor.submit(run_plan, terraform_root, tfvars_by_root): terraform_root
            for terraform_root in terraform_roots
        }
        for future in as_completed(futures):
            terraform_root, code, _, stderr = future.result()
            if code == 1:
                raise RuntimeError(f"{terraform_root['name']} plan failed:\n{stderr}")
            if code == 2:
                with_changes.append(terraform_root)
                print(f"  {terraform_root['name']}: changes detected")
            elif code == 0:
                print(f"  {terraform_root['name']}: no changes")
            else:
                raise RuntimeError(
                    f"{terraform_root['name']} returned unexpected Terraform plan "
                    f"exit code {code}\n{stderr}"
                )
    return with_changes


def show_plans(terraform_roots):
    print(f"\n{'=' * 60}\nPlanned changes:\n{'=' * 60}")
    for terraform_root in sorted(terraform_roots, key=lambda item: item["name"]):
        result = subprocess.run(
            ["terraform", "show", plan_path(terraform_root)],
            cwd=terraform_root["infra_dir"],
            capture_output=True,
            text=True,
            check=True,
        )
        print(f"\n--- {terraform_root['name']} ---\n{result.stdout}")


def apply_changes(terraform_roots):
    for terraform_root in sorted(terraform_roots, key=lambda item: item["name"]):
        print(f"\nApplying: {terraform_root['name']}")
        subprocess.run(
            ["terraform", "apply", "-parallelism=5", plan_path(terraform_root)],
            cwd=terraform_root["infra_dir"],
            check=True,
        )


def main():
    args = parse_args()
    workspace_root = args.workspace_file.resolve().parent
    terraform_roots = load_terraform_roots(workspace_root, args.manifest, args.paths)
    print(
        f"Loaded {len(terraform_roots)} Terraform roots from manifest {args.manifest}"
    )

    outputs = resolve_required_outputs(workspace_root, terraform_roots)
    image_inputs = prepare_image_inputs(terraform_roots, outputs)

    tfvars_dir = None
    try:
        push_images(workspace_root, terraform_roots, image_inputs)
        tfvars_dir, tfvars_by_root = write_tfvars(
            terraform_roots, outputs, image_inputs
        )
        print("Wrote Terraform tfvars files.")

        with_changes = plan_all(terraform_roots, tfvars_by_root)
        if not with_changes:
            print("\nNo changes to apply.")
            return

        show_plans(with_changes)
        if input("\nApply these changes? Type 'yes' to confirm: ") != "yes":
            print("Aborted.")
            return
        apply_changes(with_changes)
    finally:
        if tfvars_dir and tfvars_dir.exists():
            shutil.rmtree(tfvars_dir, ignore_errors=True)
        for terraform_root in terraform_roots:
            plan_path(terraform_root).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
