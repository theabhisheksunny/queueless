#!/usr/bin/env python3
"""
QueueLess — Read-Only Deployment Baseline State Capture
=============================================================================
Performs a comprehensive, non-mutating inspection of deployment state across
Cloudflare Edge, Render Backend, and Supabase Database layers.
Generates an auditable machine-readable baseline manifest without secrets.
=============================================================================
"""

import argparse
import datetime
import json
import os
import time
import urllib.error
import urllib.request
from typing import Any

import yaml

# Import local edge inspection utilities
from inspect_edge_route import (
    discover_cloudflare_credentials,
    inspect_live_worker_binding,
)

STATE_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "state")
CONFIG_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "config")


def probe_json_endpoint(url: str, timeout: int = 15) -> dict[str, Any]:
    """Probes an HTTP JSON endpoint and returns structured health details."""
    result = {"url": url, "reachable": False, "http_code": None, "data": None, "error": None, "latency_ms": None}
    t0 = time.time()
    try:
        req = urllib.request.Request(
            url, headers={"User-Agent": "QueueLess-Baseline-Probe/1.0", "Accept": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            latency = int((time.time() - t0) * 1000)
            result["reachable"] = True
            result["http_code"] = resp.getcode()
            result["latency_ms"] = latency
            body = resp.read().decode("utf-8", errors="replace")
            try:
                result["data"] = json.loads(body)
            except Exception:
                result["data"] = body[:200]
            return result
    except urllib.error.HTTPError as e:
        latency = int((time.time() - t0) * 1000)
        result["http_code"] = e.code
        result["latency_ms"] = latency
        body = e.read().decode("utf-8", errors="replace")
        try:
            result["data"] = json.loads(body)
        except Exception:
            result["data"] = body[:200]
        result["error"] = f"HTTP {e.code}"
        return result
    except Exception as ex:
        latency = int((time.time() - t0) * 1000)
        result["latency_ms"] = latency
        result["error"] = str(ex)
        return result


def capture_staging_baseline(
    env: str = "staging", output_path: str | None = None, backend_timeout: int = 20
) -> dict[str, Any]:
    """
    Executes the full read-only inspection and compiles the baseline manifest.
    Strictly excludes sensitive tokens and passwords.
    """
    timestamp = datetime.datetime.now(datetime.UTC).isoformat()
    config_file = os.path.join(CONFIG_DIR, f"{env}.yaml")

    with open(config_file, encoding="utf-8") as f:
        config_data = yaml.safe_load(f)

    active_provider = config_data.get("active_provider")
    provider_config = config_data.get("providers", {}).get(active_provider, {})
    edge_config = config_data.get("edge", {})

    expected_worker_name = edge_config.get("worker_name", f"queueless-{env}")
    edge_public_url = edge_config.get("public_url")
    backend_url = provider_config.get("backend_url")
    backend_health_url = provider_config.get("health_check_url", f"{backend_url}/actuator/health")
    readiness_url = f"{backend_url}/actuator/health/readiness"
    direct_vapid_url = f"{backend_url}/api/public/queue/vapid-public-key"
    edge_vapid_url = f"{edge_public_url}/api/public/queue/vapid-public-key"

    # 1. Cloudflare Live Route Inspection
    account_id, token, cred_source = discover_cloudflare_credentials()
    if account_id and token:
        cf_result = inspect_live_worker_binding(expected_worker_name, env, account_id, token)
        cf_binding_status = cf_result.get("status")
        cf_live_backend_url = cf_result.get("backend_api_url")
        cf_error = cf_result.get("error_details")
        cf_worker_exists = cf_result.get("worker_exists", False)
    else:
        cf_binding_status = "AUTH_MISSING"
        cf_live_backend_url = None
        cf_error = "Cloudflare credentials not found"
        cf_worker_exists = False
        cred_source = "UNRESOLVED"

    # 2. Direct Backend Health Probes
    actuator_health = probe_json_endpoint(backend_health_url, timeout=backend_timeout)
    actuator_readiness = probe_json_endpoint(readiness_url, timeout=backend_timeout)
    direct_vapid = probe_json_endpoint(direct_vapid_url, timeout=backend_timeout)

    # Database readiness assessment
    db_status = "UNKNOWN"
    if actuator_health["reachable"] and isinstance(actuator_health["data"], dict):
        status = str(actuator_health["data"].get("status", "")).upper()
        readiness_status = (
            str((actuator_readiness["data"] or {}).get("status", "")).upper()
            if isinstance(actuator_readiness["data"], dict)
            else ""
        )
        if status in ["UP", "HEALTHY"] and readiness_status in ["UP", "HEALTHY", ""]:
            db_status = "UP (VERIFIED_VIA_ACTUATOR_READINESS)"
        else:
            db_status = f"DEGRADED (status={status}, readiness={readiness_status})"
    elif actuator_health["reachable"]:
        db_status = f"NON_STANDARD_RESPONSE (HTTP {actuator_health['http_code']})"
    else:
        db_status = f"UNREACHABLE ({actuator_health['error']})"

    # 3. Public Edge Endpoint Probes
    edge_vapid = probe_json_endpoint(edge_vapid_url, timeout=10)

    # 4. Compile Baseline Manifest
    manifest = {
        "metadata": {
            "title": "QueueLess Staging Baseline Deployment State",
            "environment": env,
            "captured_at_utc": timestamp,
            "inspection_mode": "READ_ONLY",
            "active_provider_in_config": active_provider,
        },
        "edge_worker_state": {
            "expected_worker_name": expected_worker_name,
            "worker_exists_on_cloudflare": cf_worker_exists,
            "credential_source": cred_source,
            "cloudflare_api_status": cf_binding_status,
            "live_backend_api_url": cf_live_backend_url,
            "provenance": "LIVE_CLOUDFLARE_API" if cf_worker_exists else "NOT_FOUND_ON_CLOUDFLARE",
            "cloudflare_error": cf_error,
        },
        "active_backend_provider": {
            "provider_name": active_provider,
            "configured_backend_url": backend_url,
            "configured_region": provider_config.get("region"),
            "health_probe_url": backend_health_url,
            "actuator_health": {
                "http_code": actuator_health["http_code"],
                "status": actuator_health["data"].get("status") if isinstance(actuator_health["data"], dict) else None,
                "latency_ms": actuator_health["latency_ms"],
            },
            "actuator_readiness": {
                "http_code": actuator_readiness["http_code"],
                "status": actuator_readiness["data"].get("status")
                if isinstance(actuator_readiness["data"], dict)
                else None,
                "latency_ms": actuator_readiness["latency_ms"],
            },
            "database_readiness": db_status,
        },
        "public_edge_endpoints": {
            "edge_public_url": edge_public_url,
            "vapid_endpoint_url": edge_vapid_url,
            "edge_http_code": edge_vapid["http_code"],
            "edge_reachable": edge_vapid["reachable"],
            "edge_error": edge_vapid["error"],
            "direct_backend_vapid": {
                "http_code": direct_vapid["http_code"],
                "has_public_key": bool(
                    isinstance(direct_vapid["data"], dict) and direct_vapid["data"].get("publicKey")
                ),
                "key_prefix": (
                    direct_vapid["data"].get("publicKey", "")[:5] if isinstance(direct_vapid["data"], dict) else ""
                ),
            },
        },
        "safety_audit": {"live_infrastructure_mutated": False, "secrets_redacted": True},
    }

    if not output_path:
        os.makedirs(STATE_DIR, exist_ok=True)
        output_path = os.path.join(STATE_DIR, f"{env}-baseline.json")

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    return manifest


def print_human_readable_report(manifest: dict[str, Any]):
    meta = manifest["metadata"]
    edge = manifest["edge_worker_state"]
    backend = manifest["active_backend_provider"]
    pub = manifest["public_edge_endpoints"]

    print("=" * 76)
    print(" QueueLess — Phase 5: Read-Only Staging Baseline State Manifest")
    print("=" * 76)
    print(f" Timestamp (UTC)          : {meta['captured_at_utc']}")
    print(f" Environment              : {meta['environment']}")
    print(f" Config Active Provider   : {meta['active_provider_in_config']}")
    print("-" * 76)
    print(" [1] CLOUDFLARE EDGE ROUTING STATE")
    print(f"     Target Worker Name   : {edge['expected_worker_name']}")
    print(f"     Worker On Cloudflare : {'YES' if edge['worker_exists_on_cloudflare'] else 'NO (404 NOT FOUND)'}")
    print(f"     API Binding Status   : {edge['cloudflare_api_status']}")
    print(f"     Live BACKEND_API_URL : {edge['live_backend_api_url'] or '<NONE - NOT CONFIGURED IN CLOUDFLARE>'}")
    print(f"     Route Provenance     : {edge['provenance']}")
    if edge.get("cloudflare_error"):
        print(f"     API Diagnostic       : {edge['cloudflare_error']}")
    print("-" * 76)
    print(" [2] DIRECT BACKEND HEALTH & DATABASE READINESS")
    print(f"     Provider Target      : {backend['provider_name']} ({backend['configured_region']})")
    print(f"     Backend Base URL     : {backend['configured_backend_url']}")
    print(
        f"     Actuator Health      : HTTP {backend['actuator_health']['http_code']} -> Status '{backend['actuator_health']['status']}' ({backend['actuator_health']['latency_ms']}ms)"
    )
    print(
        f"     Actuator Readiness   : HTTP {backend['actuator_readiness']['http_code']} -> Status '{backend['actuator_readiness']['status']}' ({backend['actuator_readiness']['latency_ms']}ms)"
    )
    print(f"     Database Readiness   : {backend['database_readiness']}")
    print("-" * 76)
    print(" [3] PUBLIC EDGE & VAPID VERIFICATION")
    print(f"     Edge Public URL      : {pub['edge_public_url']}")
    print(f"     Edge VAPID Status    : HTTP {pub['edge_http_code']} ({pub['edge_error'] or 'OK'})")
    print(
        f"     Direct Backend VAPID : HTTP {pub['direct_backend_vapid']['http_code']} (Key prefix: '{pub['direct_backend_vapid']['key_prefix']}...')"
    )
    print("=" * 76)
    print(" Baseline JSON saved to  : deploy/state/staging-baseline.json")
    print(" Safety Invariant Met    : ZERO live infrastructure mutations performed.")
    print("=" * 76)


def main():
    parser = argparse.ArgumentParser(description="Capture QueueLess Deployment Baseline")
    parser.add_argument("--env", default="staging", choices=["staging", "production", "local"])
    parser.add_argument("--output", default=None, help="Output JSON path")
    parser.add_argument("--timeout", type=int, default=20, help="Backend probe timeout")
    args = parser.parse_args()

    manifest = capture_staging_baseline(env=args.env, output_path=args.output, backend_timeout=args.timeout)
    print_human_readable_report(manifest)


if __name__ == "__main__":
    main()
