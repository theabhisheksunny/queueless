#!/usr/bin/env python3
"""
QueueLess — Provider & Target Resolution Tool
Resolves declarative environment YAML files into exact deployment targets,
direct backend health URLs, and Cloudflare Worker routing configurations.
Supports dry-run, JSON export, and shell/GitHub Actions outputs.
"""

import argparse
import json
import os
import sys

import yaml

# Import validator
sys.path.insert(0, os.path.dirname(__file__))
from validate_config import validate_file


def resolve_configuration(file_path: str) -> dict:
    norm_path = os.path.normpath(file_path)
    errors = validate_file(norm_path)
    if errors:
        raise ValueError(f"Configuration validation failed for {norm_path}:\n" + "\n".join(f"  - {e}" for e in errors))

    with open(norm_path, encoding="utf-8") as f:
        data = yaml.safe_load(f)

    env = data["environment"]
    spring_profile = data["spring_profile"]
    active_provider = data["active_provider"]

    prov_cfg = data.get("providers", {}).get(active_provider, {})
    edge_cfg = data.get("edge", {})
    db_cfg = data.get("database", {})

    backend_url = prov_cfg.get("backend_url", "").rstrip("/")
    backend_health_path = prov_cfg.get("health_check_path", "/actuator/health")
    if not backend_health_path.startswith("/"):
        backend_health_path = "/" + backend_health_path
    backend_health_url = f"{backend_url}{backend_health_path}"

    edge_public_url = edge_cfg.get("public_url", "").rstrip("/")
    edge_health_path = edge_cfg.get("health_check_path", "/api/public/queue/vapid-public-key")
    if not edge_health_path.startswith("/"):
        edge_health_path = "/" + edge_health_path
    edge_health_url = f"{edge_public_url}{edge_health_path}"

    resolved = {
        "ENVIRONMENT": env,
        "SPRING_PROFILE": spring_profile,
        "ACTIVE_PROVIDER": active_provider,
        "BACKEND_SERVICE_NAME": prov_cfg.get("service_name", ""),
        "BACKEND_URL": backend_url,
        "BACKEND_HEALTH_PATH": backend_health_path,
        "BACKEND_HEALTH_URL": backend_health_url,
        "BACKEND_HEALTH_TIMEOUT_SECONDS": prov_cfg.get("health_timeout_seconds", 120),
        "PROVIDER_REGION": prov_cfg.get("region", ""),
        "EDGE_PROVIDER": edge_cfg.get("provider", ""),
        "EDGE_WORKER_NAME": edge_cfg.get("worker_name", ""),
        "EDGE_PUBLIC_URL": edge_public_url,
        "EDGE_HEALTH_PATH": edge_health_path,
        "EDGE_HEALTH_URL": edge_health_url,
        "DB_PROVIDER": db_cfg.get("provider", ""),
        "DB_POOLER_PORT": db_cfg.get("pooler_port", 5432),
        "DB_PROJECT_REF_VAR": db_cfg.get("project_ref_var", ""),
        "DB_DATASOURCE_URL_SECRET_KEY": db_cfg.get("datasource_url_secret_key", "SPRING_DATASOURCE_URL"),
    }

    # Provider specific fields
    if active_provider == "render":
        resolved["RENDER_PLAN"] = prov_cfg.get("plan", "free")
        resolved["RENDER_DEPLOY_HOOK_SECRET_KEY"] = prov_cfg.get("deploy_hook_secret_key", "")
        resolved["RENDER_CLEANUP_ON_STARTUP"] = str(prov_cfg.get("cleanup_on_startup", True)).lower()
    elif active_provider == "cloud_run":
        resolved["GCP_MIN_INSTANCES"] = prov_cfg.get("min_instances", 0)
        resolved["GCP_MAX_INSTANCES"] = prov_cfg.get("max_instances", 1)
        resolved["GCP_CPU"] = prov_cfg.get("cpu", "1")
        resolved["GCP_MEMORY"] = prov_cfg.get("memory", "1Gi")
        resolved["GCP_SERVICE_ACCOUNT_SECRET_KEY"] = prov_cfg.get("service_account_secret_key", "")

    return resolved


def print_dry_run_table(resolved: dict, config_path: str):
    print("=" * 76)
    print(f"QueueLess Deployment Resolution — DRY RUN: {config_path}")
    print("=" * 76)
    print(f"  Target Environment     : {resolved['ENVIRONMENT'].upper()}")
    print(f"  Spring Boot Profile    : {resolved['SPRING_PROFILE']}")
    print(f"  Active Provider        : {resolved['ACTIVE_PROVIDER'].upper()}")
    print("-" * 76)
    print("  [BACKEND TARGET]")
    print(f"    Service Name         : {resolved['BACKEND_SERVICE_NAME']}")
    print(f"    Direct Backend URL   : {resolved['BACKEND_URL']}")
    print(f"    Direct Health Probe  : {resolved['BACKEND_HEALTH_URL']}")
    print(f"    Health Check Timeout : {resolved['BACKEND_HEALTH_TIMEOUT_SECONDS']}s")
    print(f"    Region               : {resolved['PROVIDER_REGION']}")

    if resolved["ACTIVE_PROVIDER"] == "render":
        print(f"    Render Plan          : {resolved.get('RENDER_PLAN')}")
        print(f"    Deploy Hook Secret   : ${{{resolved.get('RENDER_DEPLOY_HOOK_SECRET_KEY')}}}")
        print(f"    Startup Catch-up     : {resolved.get('RENDER_CLEANUP_ON_STARTUP')}")
    elif resolved["ACTIVE_PROVIDER"] == "cloud_run":
        print(f"    Instances            : {resolved.get('GCP_MIN_INSTANCES')} - {resolved.get('GCP_MAX_INSTANCES')}")
        print(f"    Resources (CPU/RAM)  : {resolved.get('GCP_CPU')} vCPU / {resolved.get('GCP_MEMORY')}")
        print(f"    Deployer SA Secret   : ${{{resolved.get('GCP_SERVICE_ACCOUNT_SECRET_KEY')}}}")

    print("-" * 76)
    print("  [EDGE / CLOUDFLARE ROUTING]")
    print(f"    Edge Provider        : {resolved['EDGE_PROVIDER']}")
    print(f"    Worker Name          : {resolved['EDGE_WORKER_NAME']}")
    print(f"    Edge Public URL      : {resolved['EDGE_PUBLIC_URL']}")
    print(f"    Edge Health Route    : {resolved['EDGE_HEALTH_URL']}")
    print(f"    Edge Proxy Target    : /api/* -> {resolved['BACKEND_URL']}")

    print("-" * 76)
    print("  [DATABASE ISOLATION]")
    print(f"    Database Provider    : {resolved['DB_PROVIDER']}")
    print(f"    Database Pooler Port : {resolved['DB_POOLER_PORT']} (Session Pooler)")
    if resolved.get("DB_PROJECT_REF_VAR"):
        print(f"    Project Ref Var      : ${{{resolved['DB_PROJECT_REF_VAR']}}}")
    print(f"    Datasource URL Secret: ${{{resolved['DB_DATASOURCE_URL_SECRET_KEY']}}}")
    print("=" * 76)


def main():
    parser = argparse.ArgumentParser(description="Resolve QueueLess Deployment Configuration Target")
    parser.add_argument("config_file", help="Path to environment YAML file (e.g. deploy/config/staging.yaml)")
    parser.add_argument("--dry-run", action="store_true", help="Print human-readable deployment resolution summary")
    parser.add_argument("--json", action="store_true", help="Output resolution in JSON format")
    parser.add_argument("--export-env", action="store_true", help="Output shell 'export KEY=VAL' statements")
    parser.add_argument("--github-output", help="Path to $GITHUB_OUTPUT file to append variables")
    args = parser.parse_args()

    try:
        resolved = resolve_configuration(args.config_file)
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

    if args.dry_run or (not args.json and not args.export_env and not args.github_output):
        print_dry_run_table(resolved, args.config_file)

    if args.json:
        print(json.dumps(resolved, indent=2))

    if args.export_env:
        for k, v in resolved.items():
            print(f'export {k}="{v}"')

    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8") as gh_out:
            for k, v in resolved.items():
                gh_out.write(f"{k}={v}\n")


if __name__ == "__main__":
    main()
