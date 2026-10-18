#!/usr/bin/env python3
"""Check, apply, or remove the Zulip side-button patch without overwriting edits."""

import argparse
from pathlib import Path
import subprocess
import sys


BASE_COMMIT = "e45560dfcfc40c786c689b454ef6f4e7dc0f3eaf"
TARGET = "web/src/desktop_integration.ts"
PATCH = Path(__file__).resolve().with_name("side-mouse-navigation.patch")


def git(checkout, *args, check=True):
    result = subprocess.run(
        ["git", "-C", str(checkout), *args],
        text=True,
        capture_output=True,
        check=False,
    )
    if check and result.returncode:
        raise ValueError(result.stderr.strip() or "Git command failed")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path, help="root of a Zulip Git checkout")
    parser.add_argument("--check", action="store_true", help="validate without changing files")
    parser.add_argument("--reverse", action="store_true", help="remove only this patch")
    args = parser.parse_args()
    checkout = args.checkout.resolve()

    try:
        root = Path(git(checkout, "rev-parse", "--show-toplevel").stdout.strip()).resolve()
        if root != checkout:
            raise ValueError("Pass the root of the Zulip checkout, not a subdirectory.")
        target = checkout / TARGET
        if not target.is_file() or target.resolve() != target:
            raise ValueError(f"Expected a regular, non-symlink file at {TARGET}.")
        git(checkout, "ls-files", "--error-unmatch", "--", TARGET)

        reverse_check = git(checkout, "apply", "--reverse", "--check", str(PATCH), check=False)
        if not args.reverse and reverse_check.returncode == 0:
            print("The side-button patch is already applied; no changes made.")
            return 0
        if not args.reverse and git(checkout, "status", "--porcelain", "--", TARGET).stdout:
            raise ValueError(f"{TARGET} has local changes; commit or stash them first.")
        options = ["--reverse"] if args.reverse else []
        git(checkout, "apply", *options, "--check", str(PATCH))

        head = git(checkout, "rev-parse", "HEAD").stdout.strip()
        if head != BASE_COMMIT:
            print(f"Note: checkout is {head[:12]}; patch was tested against {BASE_COMMIT[:12]}.")
        if args.check:
            print("Patch check passed; no files changed.")
            return 0
        git(checkout, "apply", *options, str(PATCH))
        print("Patch removed." if args.reverse else "Patch applied. Rebuild/restart Zulip's frontend.")
        return 0
    except (OSError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
