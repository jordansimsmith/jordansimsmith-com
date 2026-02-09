import argparse
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

PLAN_DIR = Path("/tmp")


def find_infra_dirs(root):
    return sorted(
        [
            d / "infra"
            for d in root.iterdir()
            if d.is_dir() and (d / "infra" / "main.tf").exists()
        ]
    )


def plan_path(infra_dir):
    return PLAN_DIR / f"{infra_dir.parent.name}.tfplan"


def run_plan(infra_dir):
    subprocess.run(
        ["terraform", "init", "-input=false"],
        cwd=infra_dir,
        capture_output=True,
        check=True,
    )
    result = subprocess.run(
        [
            "terraform",
            "plan",
            f"-out={plan_path(infra_dir)}",
            "-detailed-exitcode",
            "-input=false",
        ],
        cwd=infra_dir,
        capture_output=True,
    )
    return infra_dir, result.returncode, result.stdout, result.stderr


def main():
    parser = argparse.ArgumentParser(
        description="Apply terraform across all infra directories"
    )
    parser.add_argument("root", type=Path, help="Path to a file in the workspace root")
    args = parser.parse_args()

    required_vars = ["AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY"]
    missing = [v for v in required_vars if not os.environ.get(v)]
    if missing:
        print(f"ERROR: Missing environment variables: {', '.join(missing)}")
        sys.exit(1)

    root = args.root.resolve().parent
    infra_dirs = find_infra_dirs(root)
    print(f"Found {len(infra_dirs)} infra directories in {root}")

    # Phase 1: Parallel planning
    print("Planning all directories...")
    with_changes = []
    with ThreadPoolExecutor() as executor:
        futures = {executor.submit(run_plan, d): d for d in infra_dirs}
        for future in as_completed(futures):
            infra_dir, code, stdout, stderr = future.result()
            name = infra_dir.parent.name
            if code == 1:
                print(f"ERROR: {name}\n{stderr.decode()}")
                sys.exit(1)
            elif code == 2:
                with_changes.append(infra_dir)
                print(f"  {name}: changes detected")
            else:
                print(f"  {name}: no changes")

    if not with_changes:
        print("\nNo changes to apply.")
        return

    # Phase 2: Show plans and confirm
    print(f"\n{'=' * 60}\nPlanned changes:\n{'=' * 60}")
    for infra_dir in sorted(with_changes):
        result = subprocess.run(
            ["terraform", "show", plan_path(infra_dir)],
            cwd=infra_dir,
            capture_output=True,
        )
        print(f"\n--- {infra_dir.parent.name} ---\n{result.stdout.decode()}")

    confirm = input("\nApply these changes? Type 'yes' to confirm: ")
    if confirm != "yes":
        print("Aborted.")
        return

    # Phase 3: Sequential apply
    for infra_dir in sorted(with_changes):
        print(f"\nApplying: {infra_dir.parent.name}")
        subprocess.run(
            ["terraform", "apply", plan_path(infra_dir)], cwd=infra_dir, check=True
        )
        plan_path(infra_dir).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
