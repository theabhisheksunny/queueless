#!/usr/bin/env python3
"""
QueueLess — Cloudflare Edge Traffic Rollback Tool
Restores Cloudflare Worker BACKEND_API_URL to the verified previous healthy backend target,
enforcing environment isolation and verifying edge restoration.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

ENV_WORKER_MAP = {"staging": "queueless-staging", "production": "queueless"}


def get_cloudflare_token() -> str:
    token = os.environ.get("CLOUDFLARE_API_TOKEN")
    if token and token.strip():
        return token.strip()
    if "unittest" not in sys.modules:
        appdata = os.environ.get("APPDATA")
        candidates = [
            os.path.expanduser("~/.wrangler/config/default.toml"),
            os.path.join(appdata, "xdg.config", ".wrangler", "config", "default.toml") if appdata else "",
        ]
        for p in candidates:
            if p and os.path.isfile(p):
                try:
                    with open(p, encoding="utf-8") as f:
                        for line in f:
                            if line.strip().startswith("oauth_token"):
                                val = line.split("=", 1)[1].strip().strip('"').strip("'")
                                if val:
                                    return val
                except Exception:
                    pass
    return None


def get_cloudflare_account_id() -> str:
    account_id = os.environ.get("CLOUDFLARE_ACCOUNT_ID")
    if account_id and account_id.strip():
        return account_id.strip()
    if "unittest" not in sys.modules and os.name == "nt":
        try:
            import winreg

            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Environment") as key:
                val, _ = winreg.QueryValueEx(key, "CLOUDFLARE_ACCOUNT_ID")
                if val and val.strip():
                    return val.strip()
        except Exception:
            pass
    return None


def verify_edge_vapid(edge_url: str, timeout_seconds: int = 60, interval: int = 3) -> bool:
    vapid_url = edge_url.rstrip("/") + "/api/public/queue/vapid-public-key"
    print(f"Verifying Edge VAPID endpoint: {vapid_url}...")
    start_time = time.time()

    while time.time() - start_time < timeout_seconds:
        try:
            req = urllib.request.Request(
                vapid_url, headers={"User-Agent": "QueueLess-EdgeRollbackCheck/1.0", "Accept": "application/json"}
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                status = resp.getcode()
                body = resp.read().decode("utf-8", errors="replace")

            if status == 200:
                try:
                    data = json.loads(body)
                except Exception:
                    time.sleep(interval)
                    continue

                if "error" in data:
                    print(f"  [Edge Probe] HTTP 200 returned error payload: {data.get('error')}")
                    time.sleep(interval)
                    continue

                public_key = data.get("publicKey")
                if public_key and isinstance(public_key, str) and len(public_key) > 20:
                    print(
                        f"[PASS] Edge VAPID endpoint verified successfully. Public key length: {len(public_key)} bytes."
                    )
                    return True
        except Exception:
            time.sleep(interval)

    print(f"[FAIL] Edge VAPID verification failed on {vapid_url}", file=sys.stderr)
    return False


def execute_rollback(
    env: str, worker_name: str, restore_backend_url: str, edge_public_url: str = None, dry_run: bool = False
) -> bool:
    print("=" * 70)
    print(f"QueueLess — EMERGENCY EDGE TRAFFIC ROLLBACK: {env.upper()}")
    print("=" * 70)

    expected_worker = ENV_WORKER_MAP.get(env)
    if not expected_worker or worker_name != expected_worker:
        print(
            f"FATAL: Environment boundary violation! Environment '{env}' requires worker '{expected_worker}', but got '{worker_name}'",
            file=sys.stderr,
        )
        return False

    print(f"Environment         : {env}")
    print(f"Worker Target       : {worker_name}")
    print(f"Restoring Backend   : {restore_backend_url}")
    if edge_public_url:
        print(f"Edge Public URL     : {edge_public_url}")
    print("-" * 70)

    if dry_run:
        print("[DRY-RUN] Rollback command simulated. Would execute:")
        print(f"  npx wrangler deploy --env {env} --var BACKEND_API_URL:{restore_backend_url} --keep-vars")
        if edge_public_url:
            print(f"[DRY-RUN] Would verify edge route: {edge_public_url}/api/public/queue/vapid-public-key (OK)")
        return True

    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    index_html = os.path.join(repo_root, "frontend", "dist", "index.html")
    if not os.path.isfile(index_html):
        print(
            f"FATAL: Required frontend static build artifact missing! '{index_html}' does not exist.", file=sys.stderr
        )
        print(
            "       Cloudflare Wrangler requires 'frontend/dist' containing 'index.html' (per wrangler.jsonc assets configuration).",
            file=sys.stderr,
        )
        return False

    api_token = get_cloudflare_token()
    if not api_token:
        print(
            "FATAL: Missing Cloudflare credentials (neither CLOUDFLARE_API_TOKEN nor active Wrangler session found). Cannot update Cloudflare Worker.",
            file=sys.stderr,
        )
        return False

    npx_bin = shutil.which("npx") or "npx"
    cmd = [
        npx_bin,
        "wrangler",
        "deploy",
        "--env",
        env,
        "--var",
        f"BACKEND_API_URL:{restore_backend_url}",
        "--keep-vars",
    ]

    try:
        print(f"Executing Cloudflare Worker rollback: {' '.join(cmd)}")
        result = subprocess.run(cmd, capture_output=True, text=True, check=True, shell=(os.name == "nt"))
        print("[PASS] Cloudflare Worker rollback command executed successfully.")
        if result.stdout:
            print(result.stdout)
    except subprocess.CalledProcessError as e:
        print(f"FATAL: Rollback command failed with exit code {e.returncode}:", file=sys.stderr)
        if e.stderr:
            print(e.stderr, file=sys.stderr)
        return False

    if edge_public_url:
        print(f"\nVerifying restored edge route at {edge_public_url}...")
        edge_restored = verify_edge_vapid(edge_public_url, timeout_seconds=60)
        if not edge_restored:
            print(
                f"CRITICAL FATAL: Cloudflare rollback command executed, but edge route verification failed on {edge_public_url}! Immediate operator intervention required!",
                file=sys.stderr,
            )
            return False
        print("[PASS] Edge route verified healthy after rollback.")

    return True


def main():
    parser = argparse.ArgumentParser(description="QueueLess Cloudflare Edge Traffic Rollback Tool")
    parser.add_argument("--env", required=True, choices=["staging", "production"], help="Target environment")
    parser.add_argument("--worker-name", required=True, help="Target Cloudflare Worker name")
    parser.add_argument(
        "--restore-backend-url", required=True, help="Previously verified healthy backend URL to restore"
    )
    parser.add_argument("--edge-public-url", help="Edge public URL to verify post-rollback")
    parser.add_argument("--dry-run", action="store_true", help="Simulate rollback without executing")
    args = parser.parse_args()

    success = execute_rollback(
        args.env, args.worker_name, args.restore_backend_url, edge_public_url=args.edge_public_url, dry_run=args.dry_run
    )
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
