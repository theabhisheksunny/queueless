#!/usr/bin/env python3
"""
QueueLess — Deterministic, Fail-Closed OCI Image Promoter
File: scripts/promote_image.py

Promotes verified container images from staging to production Artifact Registry:
1. Verifies the source image digest matches the approved staging digest.
2. Checks destination repository for existing tags to prevent immutable tag collisions.
3. If destination tag already exists, verifies provenance before allowing idempotent reuse.
4. If destination tag exists with mismatched digest, aborts immediately (fail-closed tamper check).
5. Copies container manifest and blobs byte-for-byte using Crane (zero recompression) with exponential retry.
6. Verifies registry-side destination digest matches source digest exactly.
"""

import argparse
import os
import subprocess
import sys
import time


def run_cmd(cmd: list[str], dry_run: bool = False) -> tuple[int, str, str]:
    if dry_run:
        print(f"[DRY-RUN] Executing: {' '.join(cmd)}")
        return 0, "", ""
    proc = subprocess.run(cmd, capture_output=True, text=True)
    return proc.returncode, proc.stdout.strip(), proc.stderr.strip()


def query_remote_tag_digest(target_repo: str, target_tag: str, gcloud_bin: str = "gcloud") -> str:
    """Queries Google Artifact Registry for an existing image tag version digest."""
    cmd = [
        gcloud_bin,
        "artifacts",
        "docker",
        "images",
        "list",
        target_repo,
        f"--filter=tags:{target_tag}",
        "--format=value(version)",
        "--limit=1",
    ]
    code, out, err = run_cmd(cmd)
    if code != 0:
        raise RuntimeError(f"Failed to query Artifact Registry for {target_repo}:{target_tag}: {err}")
    return out.strip()


def copy_with_retry(
    crane_bin: str, source_image: str, target_destination: str, max_retries: int = 3, dry_run: bool = False
) -> bool:
    """Executes crane copy with exponential backoff retry for transient network resilience."""
    copy_cmd = [crane_bin, "copy", source_image, target_destination]
    for attempt in range(1, max_retries + 1):
        print(
            f"      [Attempt {attempt}/{max_retries}] Executing crane copy: {source_image} -> {target_destination}..."
        )
        code, out, err = run_cmd(copy_cmd, dry_run=dry_run)
        if code == 0:
            return True
        print(f"      [WARNING] Attempt {attempt} failed: {err}", file=sys.stderr)
        if attempt < max_retries and not dry_run:
            backoff_delay = 2**attempt
            print(f"      Backing off for {backoff_delay}s before retry...", file=sys.stderr)
            time.sleep(backoff_delay)
    return False


def promote_image(
    source_image: str,
    target_repo: str,
    target_tag: str,
    expected_digest: str,
    crane_bin: str = "crane",
    gcloud_bin: str = "gcloud",
    max_retries: int = 3,
    dry_run: bool = False,
) -> str:
    print("=" * 70)
    print("QueueLess — Deterministic Cross-Repository Release Promoter")
    print("=" * 70)
    print(f"Source Image    : {source_image}")
    print(f"Target Repo     : {target_repo}")
    print(f"Target Tag      : {target_tag}")
    print(f"Expected Digest : {expected_digest}")
    print("-" * 70)

    # 1. Validate Expected Digest Format
    if not expected_digest.startswith("sha256:"):
        raise ValueError(f"Invalid expected_digest '{expected_digest}'. Must be sha256:...")

    # 2. Assert Source Image Contains Expected Digest
    if f"@{expected_digest}" not in source_image:
        raise ValueError(f"Source image '{source_image}' does not reference expected digest '{expected_digest}'!")

    # 3. Check Destination Repository for Existing Tag (Immutable Tag Collision & Provenance Check)
    print(f"[1/4] Checking destination repository for existing tag '{target_tag}'...")
    existing_digest = query_remote_tag_digest(target_repo, target_tag, gcloud_bin=gcloud_bin)

    if existing_digest:
        print(f"      Tag '{target_tag}' already exists in production repository.")
        print(f"      Existing Digest : {existing_digest}")
        if existing_digest == expected_digest:
            print("[2/4] [PROVENANCE_MATCH] Existing tag digest matches approved staging digest exactly.")
            print("[3/4] [IDEMPOTENT_REUSE] Skipping crane copy (preserves immutable tag stability on retries).")
            print(f"[4/4] Verified Production Image URI: {target_repo}@{existing_digest}")
            return f"{target_repo}@{existing_digest}"
        else:
            print(
                f"[FATAL_SECURITY_ERROR] Collision detected! Target tag '{target_tag}' exists in production with mismatched digest!",
                file=sys.stderr,
            )
            print(f"Existing: {existing_digest} != Expected: {expected_digest}", file=sys.stderr)
            sys.exit(1)

    print(f"      Tag '{target_tag}' is new. Proceeding with copy.")

    # 4. Perform Direct OCI Copy via Crane (Byte-for-byte manifest preservation with retries)
    target_destination = f"{target_repo}:{target_tag}"
    print("[2/4] Copying OCI manifest and blobs via Crane...")
    success = copy_with_retry(crane_bin, source_image, target_destination, max_retries=max_retries, dry_run=dry_run)
    if not success:
        print(f"[CRANE_COPY_FAILED] All {max_retries} Crane copy attempts failed! Aborting promotion.", file=sys.stderr)
        sys.exit(1)

    if dry_run:
        return f"{target_repo}@{expected_digest}"

    # 5. Authoritatively Query Destination Digest from Registry
    print("[3/4] Verifying destination digest in production Artifact Registry...")
    new_prod_digest = query_remote_tag_digest(target_repo, target_tag, gcloud_bin=gcloud_bin)

    # 6. Strict Digest Equality Assertion
    if new_prod_digest != expected_digest:
        print(
            f"[FATAL_DIGEST_MISMATCH] Destination digest '{new_prod_digest}' does not match staging digest '{expected_digest}'!",
            file=sys.stderr,
        )
        sys.exit(1)

    final_uri = f"{target_repo}@{new_prod_digest}"
    print("[4/4] [PROMOTION_VERIFIED] Destination digest matches staging digest byte-for-byte!")
    print(f"      Verified Promoted Production Image URI: {final_uri}")
    print("=" * 70)
    return final_uri


def main():
    parser = argparse.ArgumentParser(description="QueueLess OCI Image Promotion Utility")
    parser.add_argument("--source-image", required=True, help="Full source image URI including digest (@sha256:...)")
    parser.add_argument("--target-repo", required=True, help="Production repository package URI")
    parser.add_argument("--target-tag", required=True, help="Target release tag in production (e.g. sha-a1b2c3d)")
    parser.add_argument(
        "--expected-digest", required=True, help="Authoritative immutable staging image digest (sha256:...)"
    )
    parser.add_argument("--crane-bin", default="crane", help="Path to crane executable")
    parser.add_argument("--gcloud-bin", default="gcloud", help="Path to gcloud executable")
    parser.add_argument("--max-retries", type=int, default=3, help="Max retry attempts for transient copy errors")
    parser.add_argument("--dry-run", action="store_true", help="Simulate promotion steps")

    args = parser.parse_args()

    try:
        uri = promote_image(
            source_image=args.source_image,
            target_repo=args.target_repo,
            target_tag=args.target_tag,
            expected_digest=args.expected_digest,
            crane_bin=args.crane_bin,
            gcloud_bin=args.gcloud_bin,
            max_retries=args.max_retries,
            dry_run=args.dry_run,
        )
        # Write to GITHUB_OUTPUT if running inside GitHub Actions
        github_output = os.environ.get("GITHUB_OUTPUT")
        if github_output and os.path.exists(github_output):
            with open(github_output, "a") as f:
                f.write(f"promoted_image_uri={uri}\n")
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
