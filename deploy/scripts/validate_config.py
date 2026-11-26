#!/usr/bin/env python3
"""
QueueLess — Deployment Configuration Validator
Enforces strict schema compliance, single-source-of-truth provider resolution,
zero-secret leakage rules, and edge routing invariants.
"""

import argparse
import os
import re
import sys
from urllib.parse import urlparse

import yaml

ENV_PROFILE_MAP = {"local": "local", "staging": "staging", "production": "prod"}

ALLOWED_ACTIVE_PROVIDERS_V1 = {"local", "render", "cloud_run"}
VALID_IDENTIFIER_PATTERN = re.compile(r"^[A-Z0-9_]+$")
URL_PATTERN = re.compile(r"^https?://[a-zA-Z0-9.-]+(?::[0-9]+)?(?:/.*)?$")


def validate_file(file_path: str) -> list[str]:
    errors = []

    if not os.path.exists(file_path):
        return [f"File not found: {file_path}"]

    try:
        with open(file_path, encoding="utf-8") as f:
            data = yaml.safe_load(f)
    except Exception as e:
        return [f"Invalid YAML syntax in {file_path}: {e}"]

    if not isinstance(data, dict):
        return [f"Root of YAML document must be a dictionary in {file_path}"]

    # 1. Required top-level attributes
    required_top = ["version", "environment", "spring_profile", "active_provider", "database", "edge", "providers"]
    for req in required_top:
        if req not in data or data[req] is None:
            errors.append(f"Missing required top-level property '{req}' in {file_path}")

    if errors:
        return errors

    # 2. Environment & Profile alignment
    env = str(data.get("environment")).strip().lower()
    if env not in ENV_PROFILE_MAP:
        errors.append(f"Invalid environment '{env}'. Allowed: {list(ENV_PROFILE_MAP.keys())}")
    else:
        expected_profile = ENV_PROFILE_MAP[env]
        actual_profile = str(data.get("spring_profile")).strip().lower()
        if actual_profile != expected_profile:
            errors.append(
                f"spring_profile mismatch: expected '{expected_profile}' for environment '{env}', got '{actual_profile}'"
            )

    # 3. Active Provider Single-Source-of-Truth
    active_prov = str(data.get("active_provider")).strip().lower()
    if active_prov == "aws":
        errors.append("AWS provider is stubbed for future extension and not yet operational in v1")
    elif active_prov not in ALLOWED_ACTIVE_PROVIDERS_V1:
        errors.append(
            f"Invalid active_provider '{active_prov}'. Allowed operational providers in v1: {sorted(ALLOWED_ACTIVE_PROVIDERS_V1)}"
        )

    providers = data.get("providers")
    if not isinstance(providers, dict):
        errors.append("'providers' must be a dictionary")
    else:
        if active_prov in ALLOWED_ACTIVE_PROVIDERS_V1:
            if active_prov not in providers or not isinstance(providers.get(active_prov), dict):
                errors.append(f"Provider configuration block 'providers.{active_prov}' is missing or not a dictionary")
            else:
                prov_cfg = providers[active_prov]
                # Validate provider-specific required fields
                if active_prov == "render":
                    for fld in [
                        "service_name",
                        "backend_url",
                        "region",
                        "plan",
                        "deploy_hook_secret_key",
                        "health_check_path",
                    ]:
                        if fld not in prov_cfg or not prov_cfg[fld]:
                            errors.append(f"Missing required field 'providers.render.{fld}'")
                elif active_prov == "cloud_run":
                    for fld in [
                        "service_name",
                        "backend_url",
                        "region",
                        "min_instances",
                        "max_instances",
                        "cpu",
                        "memory",
                        "service_account_secret_key",
                        "health_check_path",
                    ]:
                        if fld not in prov_cfg or prov_cfg[fld] is None or prov_cfg[fld] == "":
                            errors.append(f"Missing required field 'providers.cloud_run.{fld}'")
                elif active_prov == "local":
                    for fld in ["service_name", "backend_url", "health_check_path"]:
                        if fld not in prov_cfg or not prov_cfg[fld]:
                            errors.append(f"Missing required field 'providers.local.{fld}'")

    # 4. Secret Reference Audit (Zero Plaintext Secrets Rule)
    def scan_for_secrets(obj, path=""):
        if isinstance(obj, dict):
            for k, v in obj.items():
                scan_for_secrets(v, f"{path}.{k}" if path else k)
        elif isinstance(obj, list):
            for i, elem in enumerate(obj):
                scan_for_secrets(elem, f"{path}[{i}]")
        elif isinstance(obj, str) and path.endswith(("_secret_key", "_var")):
            val = obj.strip()
            if not VALID_IDENTIFIER_PATTERN.match(val):
                errors.append(
                    f"Secret reference '{path}' must be an uppercase alphanumeric environment variable identifier, found: '{val}'"
                )
            if len(val) > 64:
                errors.append(f"Secret reference '{path}' exceeds max identifier length (64 chars): '{val}'")
            if any(
                c in val for c in [" ", "\t", "\n", "$", "{", "}", ":", ";", "/", "\\", "!", "@", "#", "%", "^", "*"]
            ):
                errors.append(f"Potential plaintext secret or complex expression leaked in '{path}': '{val}'")

    scan_for_secrets(data)

    # 5. Database Configuration Invariants
    db = data.get("database")
    if isinstance(db, dict):
        port = db.get("pooler_port")
        if port != 5432:
            errors.append(
                f"Database port must strictly be 5432 (Supabase Session Pooler / PostgreSQL default), found: {port}"
            )

        db_prov = str(db.get("provider")).strip().lower()
        if db_prov == "supabase":
            if db.get("pooler_type") != "session":
                errors.append(
                    f"Supabase database pooler_type must strictly be 'session', found: '{db.get('pooler_type')}'"
                )
            if db.get("ssl_mode") != "require":
                errors.append(f"Supabase database ssl_mode must strictly be 'require', found: '{db.get('ssl_mode')}'")
            if not db.get("project_ref_var"):
                errors.append("Supabase database configuration missing 'project_ref_var'")

    # 6. Edge Invariants
    edge = data.get("edge")
    if isinstance(edge, dict):
        public_url = edge.get("public_url")
        if not public_url or not URL_PATTERN.match(str(public_url)):
            errors.append(f"edge.public_url must be a valid URL, found: '{public_url}'")

        health_path = str(edge.get("health_check_path", ""))
        if not health_path.startswith("/api/"):
            errors.append(
                f"edge.health_check_path must start with '/api/' (Cloudflare Worker only proxies /api/* to backend), found: '{health_path}'"
            )

    # 7. Backend URL formats
    if isinstance(providers, dict):
        for p_name, p_val in providers.items():
            if isinstance(p_val, dict) and "backend_url" in p_val:
                b_url = str(p_val["backend_url"])
                if not URL_PATTERN.match(b_url):
                    errors.append(f"providers.{p_name}.backend_url must be a valid URL, found: '{b_url}'")
                parsed = urlparse(b_url)
                if env != "local" and parsed.scheme != "https":
                    errors.append(
                        f"Non-local environment '{env}' requires https scheme for providers.{p_name}.backend_url, found: '{b_url}'"
                    )

    return errors


def main():
    parser = argparse.ArgumentParser(description="Validate QueueLess Deployment Configurations")
    parser.add_argument("files", nargs="*", help="Paths to YAML configuration files")
    parser.add_argument("--all", action="store_true", help="Validate all standard environments under deploy/config/")
    args = parser.parse_args()

    files_to_check = list(args.files)
    if args.all or not files_to_check:
        base_dir = os.path.join(os.path.dirname(__file__), "..", "config")
        standard_files = [
            os.path.join(base_dir, "local.yaml"),
            os.path.join(base_dir, "staging.yaml"),
            os.path.join(base_dir, "production.yaml"),
        ]
        files_to_check = [f for f in standard_files if os.path.exists(f)]

    if not files_to_check:
        print("ERROR: No configuration files specified or found to validate.", file=sys.stderr)
        sys.exit(1)

    all_passed = True
    print("=" * 70)
    print("QueueLess — Deployment Configuration Validator")
    print("=" * 70)

    for f_path in files_to_check:
        norm_path = os.path.normpath(f_path)
        errors = validate_file(norm_path)
        if errors:
            all_passed = False
            print(f"[FAIL] {norm_path}:")
            for err in errors:
                print(f"       - {err}")
        else:
            print(f"[PASS] {norm_path} is strictly valid.")

    print("=" * 70)
    if not all_passed:
        print("RESULT: VALIDATION FAILED. Aborting deployment gate.", file=sys.stderr)
        sys.exit(1)
    else:
        print("RESULT: ALL CONFIGURATIONS VALID.")
        sys.exit(0)


if __name__ == "__main__":
    main()
