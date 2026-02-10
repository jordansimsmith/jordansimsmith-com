import argparse
import json
import shutil
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

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
    return parser.parse_args()


def load_terraform_roots(workspace_root, manifest_path):
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
            }
        )

    return terraform_roots


def resolve_artifacts(workspace_root, terraform_roots):
    labels = sorted(
        {
            label
            for terraform_root in terraform_roots
            for label in terraform_root["artifacts"].values()
        }
    )
    if not labels:
        return {}

    print(f"Resolving {len(labels)} artifact labels...")
    subprocess.run(
        ["bazel", "build", *labels],
        cwd=workspace_root,
        capture_output=True,
        text=True,
        check=True,
    )

    cquery_expr = '"%s\\t%s" % (str(target.label), ",".join([f.path for f in target.files.to_list()]))'
    query_expression = f"set({' '.join(labels)})"
    cquery_output = subprocess.run(
        [
            "bazel",
            "cquery",
            "--output=starlark",
            f"--starlark:expr={cquery_expr}",
            query_expression,
        ],
        cwd=workspace_root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout

    resolved_labels = {}
    for line in cquery_output.splitlines():
        line = line.strip()
        if "\t" not in line:
            continue

        label, file_paths_csv = line.split("\t", 1)
        if label.startswith("@@//"):
            label = label[2:]
        elif label.startswith("@//"):
            label = label[1:]

        file_paths = [path for path in file_paths_csv.split(",") if path]
        if len(file_paths) != 1:
            raise RuntimeError(
                f"Expected exactly one output path for `{label}`, found {len(file_paths)}: {file_paths}"
            )

        resolved_labels[label] = str(workspace_root / file_paths[0])

    return resolved_labels


def write_tfvars(terraform_roots, resolved_labels):
    tfvars_dir = Path(tempfile.mkdtemp(prefix="terraform-artifacts-"))
    tfvars_by_root = {}
    for terraform_root in terraform_roots:
        artifacts = {
            artifact_key: resolved_labels[label]
            for artifact_key, label in terraform_root["artifacts"].items()
        }

        tfvars_path = (
            tfvars_dir
            / f"{'_'.join(terraform_root['relative_path'].parts)}.tfvars.json"
        )
        tfvars_path.write_text(json.dumps({"artifacts": artifacts}, indent=2) + "\n")
        tfvars_by_root[terraform_root["infra_dir"]] = tfvars_path

    return tfvars_dir, tfvars_by_root


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

    plan_file = PLAN_DIR / f"{terraform_root['name']}.tfplan"
    result = subprocess.run(
        [
            "terraform",
            "plan",
            f"-out={plan_file}",
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
                    f"{terraform_root['name']} returned unexpected terraform plan exit code {code}\n{stderr}"
                )
    return with_changes


def show_plans(with_changes):
    print(f"\n{'=' * 60}\nPlanned changes:\n{'=' * 60}")
    for terraform_root in sorted(with_changes, key=lambda item: item["name"]):
        plan_file = PLAN_DIR / f"{terraform_root['name']}.tfplan"
        result = subprocess.run(
            ["terraform", "show", plan_file],
            cwd=terraform_root["infra_dir"],
            capture_output=True,
            text=True,
            check=False,
        )
        print(f"\n--- {terraform_root['name']} ---\n{result.stdout}")


def apply_changes(with_changes):
    for terraform_root in sorted(with_changes, key=lambda item: item["name"]):
        plan_file = PLAN_DIR / f"{terraform_root['name']}.tfplan"
        print(f"\nApplying: {terraform_root['name']}")
        subprocess.run(
            ["terraform", "apply", plan_file],
            cwd=terraform_root["infra_dir"],
            check=True,
        )
        plan_file.unlink(missing_ok=True)


def main():
    args = parse_args()
    workspace_root = args.workspace_file.resolve().parent
    terraform_roots = load_terraform_roots(workspace_root, args.manifest)
    print(
        f"Loaded {len(terraform_roots)} Terraform roots from manifest {args.manifest}"
    )
    resolved_labels = resolve_artifacts(workspace_root, terraform_roots)

    tfvars_dir = None
    try:
        tfvars_dir, tfvars_by_root = write_tfvars(terraform_roots, resolved_labels)
        print(f"Wrote artifact tfvars files.")
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


if __name__ == "__main__":
    main()
