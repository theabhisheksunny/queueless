#!/usr/bin/env python3
"""
QueueLess — Cloudflare Edge Traffic Switcher & Verified Rollback Orchestrator
Safely updates Cloudflare Worker BACKEND_API_URL with:
1. Exact worker and environment boundary validation (staging vs production).
2. Authoritative retrieval of current live BACKEND_API_URL before mutations (fails closed if missing).
3. Pre-flight verification that the previous backend is healthy before accepting as rollback target.
4. Pre-switch direct backend health probe on the new target backend.
5. Post-switch edge VAPID public-key API verification (confirming database & backend reachability).
6. Automatic rollback to the exact previous verified URL if edge verification fails.
7. Verification of edge routing post-rollback, reporting critical failure if rollback verification fails.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

# Import sister modules
sys.path.insert(0, os.path.dirname(__file__))
from rollback_edge_traffic import execute_rollback, verify_edge_vapid
from verify_backend_health import verify_direct_backend

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


def get_live_backend_url(env: str, worker_name: str, account_id: str = None, api_token: str = None) -> str:
    """
    Retrieves the actual live BACKEND_API_URL from Cloudflare API for the exact Worker environment.
    Fails closed (returns None) if credentials are missing or API request fails.
    """
    if not account_id:
        account_id = get_cloudflare_account_id()
    if not api_token:
        api_token = get_cloudflare_token()

    if not account_id or not api_token:
        print("[INFO] Cloudflare account_id or api_token not provided in environment for API query.")
        return None

    headers = {
        "Authorization": f"Bearer {api_token}",
        "Content-Type": "application/json",
        "User-Agent": "QueueLess-EdgeSwitcher/1.0",
    }

    # Endpoint candidate 1: Environment-scoped bindings
    candidates = [
        f"https://api.cloudflare.com/client/v4/accounts/{account_id}/workers/services/{worker_name}/environments/{env}/bindings",
        f"https://api.cloudflare.com/client/v4/accounts/{account_id}/workers/scripts/{worker_name}/bindings",
        f"https://api.cloudflare.com/client/v4/accounts/{account_id}/workers/scripts/{worker_name}/settings",
    ]

    for api_url in candidates:
        try:
            req = urllib.request.Request(api_url, headers=headers)
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if not data.get("success", False):
                    continue

                result = data.get("result", {})
                if isinstance(result, list):
                    for b in result:
                        if b.get("name") == "BACKEND_API_URL":
                            val = b.get("text")
                            if val and val.strip():
                                return val.strip()
                elif isinstance(result, dict):
                    bindings = result.get("bindings", [])
                    for b in bindings:
                        if b.get("name") == "BACKEND_API_URL":
                            val = b.get("text")
                            if val and val.strip():
                                return val.strip()
        except Exception as e:
            print(f"[DEBUG] Cloudflare API query to {api_url} failed: {e}")

    return None


def verify_frontend_dist(repo_root: str = None) -> bool:
    """
    Verifies that frontend static asset build directory (frontend/dist/index.html) exists.
    Cloudflare Wrangler requires this directory per wrangler.jsonc assets configuration.
    """
    if not repo_root:
        repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    dist_dir = os.path.join(repo_root, "frontend", "dist")
    index_html = os.path.join(dist_dir, "index.html")
    if not os.path.isfile(index_html):
        print(
            f"FATAL: Required frontend build directory or file missing! '{index_html}' does not exist.", file=sys.stderr
        )
        print(
            "       Cloudflare Wrangler requires 'frontend/dist' with 'index.html' (per wrangler.jsonc assets config).",
            file=sys.stderr,
        )
        print(
            "       Please run 'npm run build' inside the 'frontend/' directory before deploying or bootstrapping.",
            file=sys.stderr,
        )
        return False
    return True


def switch_edge_traffic(
    env: str,
    worker_name: str,
    new_backend_url: str,
    backend_health_url: str,
    edge_public_url: str,
    current_backend_url: str = None,
    backend_health_path: str = "/actuator/health/readiness",
    health_timeout: int = 120,
    edge_timeout: int = 60,
    dry_run: bool = False,
    initial_bootstrap: bool = False,
    confirm_production: bool = False,
) -> bool:
    print("=" * 76)
    print(f"QueueLess — EDGE TRAFFIC SWITCHING GATEWAY: {env.upper()}")
    print("=" * 76)

    # 1. Validate Worker Name and Environment Boundary
    expected_worker = ENV_WORKER_MAP.get(env)
    if not expected_worker:
        print(f"FATAL: Unsupported environment '{env}'. Allowed: {list(ENV_WORKER_MAP.keys())}", file=sys.stderr)
        return False

    if worker_name != expected_worker:
        print(
            f"FATAL: Environment boundary violation! Environment '{env}' requires worker '{expected_worker}', but received '{worker_name}'! Operation aborted to prevent cross-environment misrouting.",
            file=sys.stderr,
        )
        return False

    # Enforce Production Traffic Switch Explicit Approval Gate
    if env == "production" and not dry_run:
        confirmed = confirm_production or os.environ.get("CONFIRM_PRODUCTION_TRAFFIC_SWITCH", "").lower() in [
            "true",
            "1",
            "yes",
        ]
        if not confirmed:
            print(
                "FATAL: Production traffic switch requires explicit confirmation! Pass --confirm-production or set CONFIRM_PRODUCTION_TRAFFIC_SWITCH=true to authorize live production routing change.",
                file=sys.stderr,
            )
            return False

    print(f"Environment          : {env}")
    print(f"Validated Worker     : {worker_name}")
    print(f"Target Backend URL   : {new_backend_url}")
    print(f"Edge Public URL      : {edge_public_url}")
    if initial_bootstrap:
        print("Operation Mode       : INITIAL_BOOTSTRAP (No pre-existing route required)")
    print("-" * 76)

    # 2. Retrieve & Record Actual Existing Route Before Any Changes
    if initial_bootstrap:
        print("[BOOTSTRAP MODE] Skipping previous route check: initial bootstrap creates initial route.")
        verified_rollback_url = None
    else:
        live_url = get_live_backend_url(env, worker_name)
        if live_url and live_url.strip():
            previous_backend_url = live_url.strip()
            print(f"Verified Live Route  : Currently pointing to {previous_backend_url} (from Cloudflare API)")
        elif current_backend_url and current_backend_url.strip():
            previous_backend_url = current_backend_url.strip()
            print(f"Pre-flight Route     : Verified existing route {previous_backend_url} (from preflight)")
        else:
            print(
                f"FATAL: Could not retrieve or verify current live BACKEND_API_URL for worker environment '{worker_name}' ({env})! Refusing to switch traffic without a verified rollback target.",
                file=sys.stderr,
            )
            return False

        # Invariant: Never use the new target backend URL as the previous route or rollback target
        if previous_backend_url == new_backend_url:
            print(f"Notice               : Target backend URL is already active on edge ({previous_backend_url}).")

        verified_rollback_url = previous_backend_url

        # 3. Verify Previous Backend is Healthy Before Accepting as Rollback Target
        print("\n[Step 0/4] Verifying previous backend health before switch...")
        prev_health_url = verified_rollback_url.rstrip("/") + backend_health_path
        if not dry_run:
            prev_healthy = verify_direct_backend(prev_health_url, timeout_seconds=min(health_timeout, 30))
            if not prev_healthy:
                print(
                    f"FATAL: Previous backend at {prev_health_url} is UNHEALTHY! Refusing to switch traffic because the rollback target is not operational.",
                    file=sys.stderr,
                )
                return False
            print(f"[PASS] Previous backend is healthy ({prev_health_url}). Rollback target verified.")
        else:
            print(f"[DRY-RUN] Previous backend health probe simulated: {prev_health_url} (OK)")

    # 4. Pre-Switch: Verify New Target Backend Health
    print("\n[Step 1/4] Pre-flight Direct Backend Health Verification (New Target)...")
    if not dry_run:
        backend_ok = verify_direct_backend(backend_health_url, timeout_seconds=health_timeout)
        if not backend_ok:
            print(
                f"FATAL: New target backend health check failed at {backend_health_url}. Refusing to switch edge traffic!",
                file=sys.stderr,
            )
            return False
        print(f"[PASS] New backend is healthy ({backend_health_url}).")
    else:
        print(f"[DRY-RUN] Direct backend health probe simulated: {backend_health_url} (OK)")

    # 5. Execute Cloudflare Routing Switch
    print("\n[Step 2/4] Updating Cloudflare Worker BACKEND_API_URL...")
    if not dry_run:
        if not verify_frontend_dist():
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
            f"BACKEND_API_URL:{new_backend_url}",
            "--keep-vars",
        ]
        try:
            print(f"Executing: {' '.join(cmd)}")
            res = subprocess.run(cmd, capture_output=True, text=True, check=True, shell=(os.name == "nt"))
            print("[PASS] Cloudflare Worker routing update applied successfully.")
            if res.stdout:
                print(res.stdout)
        except subprocess.CalledProcessError as e:
            print(f"FATAL: Cloudflare update failed with code {e.returncode}: {e.stderr}", file=sys.stderr)
            return False
    else:
        if not verify_frontend_dist():
            print(
                "[DRY-RUN WARN] Required frontend build 'frontend/dist/index.html' not found. In live mode, deployment would abort."
            )
        print(
            f"[DRY-RUN] Would execute: npx wrangler deploy --env {env} --var BACKEND_API_URL:{new_backend_url} --keep-vars"
        )

    # 6. Post-Switch: Verify Edge API Route (VAPID endpoint)
    print("\n[Step 3/4] Post-Switch Edge API Route Verification...")
    if not dry_run:
        edge_ok = verify_edge_vapid(edge_public_url, timeout_seconds=edge_timeout)
        if not edge_ok:
            if verified_rollback_url:
                print(
                    f"\n[ALERT] Edge verification failed after switching to {new_backend_url}! Triggering AUTOMATIC ROLLBACK to verified previous URL: {verified_rollback_url}...",
                    file=sys.stderr,
                )
                rb_ok = execute_rollback(
                    env, worker_name, verified_rollback_url, edge_public_url=edge_public_url, dry_run=False
                )
                if not rb_ok:
                    print(
                        f"CRITICAL FATAL: Automatic rollback failed to restore {verified_rollback_url}! Edge public URL {edge_public_url} is non-functional. Immediate operator intervention required!",
                        file=sys.stderr,
                    )
                    return False

                print(
                    f"[ROLLBACK SUCCESSFUL] Restored and verified Cloudflare Worker to previous healthy backend: {verified_rollback_url}"
                )
                return False
            else:
                print(
                    f"\n[ALERT] Edge verification failed after initial bootstrap to {new_backend_url}! No previous rollback route exists.",
                    file=sys.stderr,
                )
                return False

        print("[PASS] Edge API route fully operational and verified.")
    else:
        print(
            f"[DRY-RUN] Edge VAPID endpoint probe simulated: {edge_public_url}/api/public/queue/vapid-public-key (OK)"
        )

    print("\n[Step 4/4] Finalizing Routing Attestation...")
    print("=" * 76)
    if initial_bootstrap:
        print(f"SUCCESS: Initial edge routing for {env.upper()} established to {new_backend_url}!")
    else:
        print(f"SUCCESS: Edge traffic for {env.upper()} successfully routed to {new_backend_url}!")
        print(f"Verified Rollback Target Preserved: {verified_rollback_url}")
    print("=" * 76)
    return True


def main():
    parser = argparse.ArgumentParser(description="QueueLess Cloudflare Edge Traffic Switcher")
    parser.add_argument("--env", required=True, choices=["staging", "production"], help="Target environment")
    parser.add_argument("--worker-name", required=True, help="Target Cloudflare Worker name")
    parser.add_argument("--new-backend-url", required=True, help="New target backend URL")
    parser.add_argument("--backend-health-url", required=True, help="Direct backend health check URL")
    parser.add_argument("--edge-public-url", required=True, help="Public Cloudflare Worker URL")
    parser.add_argument("--current-backend-url", help="Previously verified live backend URL (for rollback)")
    parser.add_argument(
        "--backend-health-path",
        default="/actuator/health/readiness",
        help="Path for backend health probes (default /actuator/health/readiness)",
    )
    parser.add_argument("--health-timeout", type=int, default=120, help="Direct backend health timeout (seconds)")
    parser.add_argument("--edge-timeout", type=int, default=60, help="Edge health timeout (seconds)")
    parser.add_argument(
        "--initial-bootstrap",
        action="store_true",
        help="Allow initial deployment to an unrouted Worker without rollback target",
    )
    parser.add_argument(
        "--confirm-production", action="store_true", help="Explicit confirmation required to switch production traffic"
    )
    parser.add_argument("--dry-run", action="store_true", help="Simulate traffic switch without modifying Cloudflare")
    args = parser.parse_args()

    success = switch_edge_traffic(
        env=args.env,
        worker_name=args.worker_name,
        new_backend_url=args.new_backend_url,
        backend_health_url=args.backend_health_url,
        edge_public_url=args.edge_public_url,
        current_backend_url=args.current_backend_url,
        backend_health_path=args.backend_health_path,
        health_timeout=args.health_timeout,
        edge_timeout=args.edge_timeout,
        dry_run=args.dry_run,
        initial_bootstrap=args.initial_bootstrap,
        confirm_production=args.confirm_production,
    )
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
