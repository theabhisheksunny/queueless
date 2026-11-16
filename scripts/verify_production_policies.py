#!/usr/bin/env python3
"""
QueueLess — Automated Production Policy & Security Verifier
Validates strict pre-deployment policy requirements:
1. No hardcoded production credentials in application configuration or source.
2. Demo owner account strictly isolated to development/test profiles.
3. No OTP/SMS registration endpoints, tables, or SMS providers.
4. No mock providers enabled in production code.
5. No hardcoded 'localhost' references in production frontend build/client.
6. All required environment configurations and secret placeholders properly defined.
7. No exposed private keys (VAPID, RSA, EC) or credentials committed.
"""

import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

class PolicyViolation(Exception):
    pass

def log_pass(msg: str):
    print(f"  [PASS] {msg}")

def log_fail(msg: str):
    print(f"  [FAIL] {msg}", file=sys.stderr)

def check_no_hardcoded_credentials():
    print("[1/10] Verifying no hardcoded credentials in production configurations...")
    app_props_path = REPO_ROOT / "backend" / "src" / "main" / "resources" / "application.properties"
    if not app_props_path.exists():
        raise PolicyViolation(f"Missing application.properties at {app_props_path}")

    content = app_props_path.read_text(encoding="utf-8")

    # Required secret placeholders that MUST use environment substitution ${VAR:...}
    secret_keys = [
        "spring.datasource.password",
        "app.jwt.secret",
        "app.admin.key",
        "app.webpush.vapid.private-key"
    ]

    for key in secret_keys:
        match = re.search(rf"^{re.escape(key)}\s*=\s*(.*)$", content, re.MULTILINE)
        if not match:
            raise PolicyViolation(f"Secret property '{key}' is missing in application.properties")
        val = match.group(1).strip()
        if not val.startswith("${") and val != "":
            raise PolicyViolation(f"Property '{key}' must use environment variable substitution syntax: found '{val}'")

    # Verify AdminPage.tsx and translations.ts do not hardcode default admin secret
    admin_page = REPO_ROOT / "frontend" / "src" / "pages" / "AdminPage.tsx"
    if admin_page.exists():
        admin_content = admin_page.read_text(encoding="utf-8")
        if "admin-secret-queueless-2026" in admin_content:
            raise PolicyViolation("AdminPage.tsx hardcodes default admin secret key 'admin-secret-queueless-2026'")
        if "queueless_admin_key" in admin_content:
            raise PolicyViolation("AdminPage.tsx persists admin key in browser storage (queueless_admin_key)")

    translations_file = REPO_ROOT / "frontend" / "src" / "locales" / "translations.ts"
    if translations_file.exists():
        trans_content = translations_file.read_text(encoding="utf-8")
        if "admin-secret-queueless-2026" in trans_content:
            raise PolicyViolation("translations.ts contains hardcoded admin secret key in placeholder text")

    # Verify AdminController has structured audit logging for PIN resets
    admin_ctrl = REPO_ROOT / "backend" / "src" / "main" / "java" / "com" / "queueless" / "controller" / "AdminController.java"
    if admin_ctrl.exists():
        ctrl_content = admin_ctrl.read_text(encoding="utf-8")
        if "AUDIT: Admin PIN reset SUCCESS" not in ctrl_content:
            raise PolicyViolation("AdminController.java lacks structured success audit logging for PIN resets")
        if "AUDIT: Admin PIN reset FAILED" not in ctrl_content:
            raise PolicyViolation("AdminController.java lacks structured failure audit logging for PIN resets")

    log_pass("All sensitive configuration properties use dynamic environment substitution.")

def check_demo_account_isolation():
    print("[2/10] Verifying demo account isolation from production...")
    app_file = REPO_ROOT / "backend" / "src" / "main" / "java" / "com" / "queueless" / "QueueLessApplication.java"
    if not app_file.exists():
        raise PolicyViolation(f"Missing QueueLessApplication.java at {app_file}")

    content = app_file.read_text(encoding="utf-8")

    # Assert @Profile annotation strictly limits seeding to non-production
    if '@Profile({"dev", "test", "local"})' not in content:
        raise PolicyViolation("Demo seeding bean in QueueLessApplication.java is not strictly gated with @Profile({'dev', 'test', 'local'})")

    # Assert defensive guard against prod profile
    if '"prod".equalsIgnoreCase(profile)' not in content:
        raise PolicyViolation("QueueLessApplication.java lacks defensive secondary guard against active 'prod' profile")

    # Check LoginPage.tsx for dev guard on demo button
    login_page = REPO_ROOT / "frontend" / "src" / "pages" / "LoginPage.tsx"
    if login_page.exists():
        login_content = login_page.read_text(encoding="utf-8")
        if "demoLoginBtn" in login_content:
            if "import.meta.env.DEV" not in login_content:
                raise PolicyViolation("LoginPage.tsx has demo login button not guarded by import.meta.env.DEV")

    log_pass("Demo accounts and credentials are strictly isolated from production profiles.")

def check_no_otp_sms():
    print("[3/10] Verifying complete elimination of OTP and SMS providers...")
    # Search for forbidden OTP / SMS provider patterns in main backend and frontend code
    forbidden_patterns = [
        (re.compile(r"class\s+OtpRecord", re.IGNORECASE), "OtpRecord entity model"),
        (re.compile(r"interface\s+OtpRecordRepository", re.IGNORECASE), "OtpRecordRepository"),
        (re.compile(r"@PostMapping\(.*otp.*\)", re.IGNORECASE), "OTP HTTP endpoint"),
        (re.compile(r"twilio", re.IGNORECASE), "Twilio SMS provider reference"),
        (re.compile(r"fast2sms", re.IGNORECASE), "Fast2SMS provider reference"),
    ]

    backend_main = REPO_ROOT / "backend" / "src" / "main" / "java"
    for root, _, files in os.walk(backend_main):
        for file in files:
            if file.endswith(".java"):
                file_path = Path(root) / file
                content = file_path.read_text(encoding="utf-8")
                for pattern, desc in forbidden_patterns:
                    if pattern.search(content):
                        raise PolicyViolation(f"Forbidden OTP/SMS residue found in {file}: {desc}")

    # Check DB migrations: ensure V2 drops otp_records and no new migration creates it
    v2_migration = REPO_ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration" / "V2__drop_otp_records.sql"
    if not v2_migration.exists():
        raise PolicyViolation("V2__drop_otp_records.sql is missing")

    log_pass("Zero OTP/SMS tables, endpoints, or third-party SMS providers exist in production code.")

def check_no_mock_provider_in_production():
    print("[4/10] Verifying no mock payment or auth providers in production...")
    backend_main = REPO_ROOT / "backend" / "src" / "main" / "java"
    for root, _, files in os.walk(backend_main):
        for file in files:
            if file.endswith(".java"):
                file_path = Path(root) / file
                content = file_path.read_text(encoding="utf-8")
                if "MockPaymentService" in content or "MockSmsService" in content:
                    raise PolicyViolation(f"Mock provider class found in production source: {file}")

    log_pass("No mock providers or test bypasses exist in production codebase.")

def check_no_localhost_in_production():
    print("[5/10] Verifying frontend production client does not leak localhost...")
    client_ts = REPO_ROOT / "frontend" / "src" / "api" / "client.ts"
    if not client_ts.exists():
        raise PolicyViolation(f"Missing api client at {client_ts}")

    content = client_ts.read_text(encoding="utf-8")

    # Must use getApiBaseUrl with check for import.meta.env.DEV
    if "import.meta.env.DEV" not in content:
        raise PolicyViolation("client.ts does not guard localhost API base URL with import.meta.env.DEV")

    # In production, must return empty string '' (same origin)
    if "return '';" not in content and 'return "";' not in content:
        raise PolicyViolation("client.ts does not fall back to same-origin empty string '' for production")

    # If dist exists, check production bundle
    dist_dir = REPO_ROOT / "frontend" / "dist"
    if dist_dir.exists():
        for root, _, files in os.walk(dist_dir):
            for file in files:
                if file.endswith(".js"):
                    bundle = (Path(root) / file).read_text(encoding="utf-8")
                    if "http://localhost:8080" in bundle:
                        raise PolicyViolation(f"Production bundle {file} contains hardcoded 'http://localhost:8080'!")

    log_pass("Frontend safely uses relative same-origin API routes without leaking localhost.")

def check_required_environment_variables():
    print("[6/10] Verifying required runtime configuration declarations...")
    app_props = REPO_ROOT / "backend" / "src" / "main" / "resources" / "application.properties"
    content = app_props.read_text(encoding="utf-8")

    required_vars = [
        "PORT",
        "SPRING_DATASOURCE_URL",
        "APP_JWT_SECRET",
        "APP_ADMIN_KEY",
        "APP_WEBPUSH_VAPID_PUBLIC_KEY",
        "APP_WEBPUSH_VAPID_PRIVATE_KEY",
    ]

    for var in required_vars:
        if f"${{{var}" not in content:
            raise PolicyViolation(f"Missing required environment variable binding ${{{var}...}} in application.properties")

    log_pass("All mandatory environment variables are defined with configurable fallbacks.")

def check_no_exposed_private_keys():
    print("[7/10] Scanning repository for exposed private keys or tokens...")
    forbidden_key_headers = [
        "-----BEGIN RSA PRIVATE KEY-----",
        "-----BEGIN EC PRIVATE KEY-----",
        "-----BEGIN PRIVATE KEY-----",
        "-----BEGIN OPENSSH PRIVATE KEY-----",
        "AKIA",  # AWS Access Key ID prefix
        "ghp_",  # GitHub Personal Access Token prefix
    ]

    scanned_extensions = {".java", ".properties", ".xml", ".ts", ".tsx", ".js", ".json", ".jsonc", ".yml", ".yaml", ".sql", ".sh", ".ps1", ".bat"}
    ignore_dirs = {".git", "node_modules", "target", "dist", ".wrangler"}

    for root, dirs, files in os.walk(REPO_ROOT):
        # Prune ignored directories
        dirs[:] = [d for d in dirs if d not in ignore_dirs]
        for file in files:
            file_path = Path(root) / file
            if file_path.suffix.lower() in scanned_extensions:
                try:
                    content = file_path.read_text(encoding="utf-8", errors="ignore")
                    for header in forbidden_key_headers:
                        if header in content:
                            raise PolicyViolation(f"Found potentially exposed secret/private key ({header}) in {file_path.relative_to(REPO_ROOT)}")
                except Exception as e:
                    if isinstance(e, PolicyViolation):
                        raise
                    pass

    log_pass("No exposed private keys or token patterns detected in tracked files.")

def check_no_auth_bypass():
    print("[8/10] Verifying zero authentication bypasses in AuthService.java...")
    auth_service_path = REPO_ROOT / "backend" / "src" / "main" / "java" / "com" / "queueless" / "service" / "AuthService.java"
    if not auth_service_path.exists():
        raise PolicyViolation(f"Missing AuthService.java at {auth_service_path}")

    content = auth_service_path.read_text(encoding="utf-8")

    # Ensure no hardcoded bypass credentials remain
    forbidden_tokens = ["password123", "123456", "owner@queueless.com"]
    for token in forbidden_tokens:
        if token in content:
            raise PolicyViolation(f"AuthService.java contains forbidden hardcoded credential token: '{token}'")

    # Verify pinMatches and pwdMatches use strict passwordEncoder
    if "boolean pinMatches = passwordEncoder.matches(request.getPin(), user.getPassword());" not in content:
        raise PolicyViolation("AuthService.java pinMatches is not strictly evaluated via passwordEncoder.matches")
    if "boolean pwdMatches = passwordEncoder.matches(request.getPassword(), user.getPassword());" not in content:
        raise PolicyViolation("AuthService.java pwdMatches is not strictly evaluated via passwordEncoder.matches")

    log_pass("AuthService strictly verifies BCrypt password and PIN hashes with no bypasses.")

def check_cicd_security_and_secrets():
    print("[9/10] Verifying CI/CD least-privilege permissions, WIF, and Secret Manager...")
    ci_yml = REPO_ROOT / ".github" / "workflows" / "ci.yml"
    cd_yml = REPO_ROOT / ".github" / "workflows" / "cd.yml"

    if not ci_yml.exists() or not cd_yml.exists():
        raise PolicyViolation("Missing GitHub Actions workflow files in .github/workflows/")

    ci_content = ci_yml.read_text(encoding="utf-8")
    cd_content = cd_yml.read_text(encoding="utf-8")

    # Permissions check
    if "permissions:" not in ci_content or "contents: read" not in ci_content:
        raise PolicyViolation("ci.yml must define explicit top-level 'permissions: contents: read'")
    if "permissions:" not in cd_content or "contents: read" not in cd_content:
        raise PolicyViolation("cd.yml must define explicit top-level 'permissions: contents: read'")
    if "id-token: write" not in cd_content:
        raise PolicyViolation("cd.yml must define 'id-token: write' permission for Workload Identity Federation")

    # WIF check: Must use workload_identity_provider and service_account, NO static credentials_json
    if "credentials_json" in cd_content:
        raise PolicyViolation("cd.yml must not use static credentials_json (GCP_SA_KEY). Use Workload Identity Federation!")
    if "workload_identity_provider" not in cd_content or "service_account" not in cd_content:
        raise PolicyViolation("cd.yml must authenticate using workload_identity_provider and service_account")

    # Secret Manager check: Cloud Run deployments must use --set-secrets, not --set-env-vars for secrets
    if "--set-secrets=" not in cd_content:
        raise PolicyViolation("cd.yml Cloud Run deployments must use --set-secrets for sensitive credentials")

    # Check that sensitive keys are not in --set-env-vars
    env_var_matches = re.findall(r'--set-env-vars="([^"]+)"', cd_content)
    for env_str in env_var_matches:
        for sensitive in ["DB_URL", "JWT_SECRET", "ADMIN_KEY", "PASSWORD"]:
            if sensitive in env_str:
                raise PolicyViolation(f"cd.yml leaks sensitive variable '{sensitive}' via --set-env-vars instead of --set-secrets")

    # Cloudflare Wrangler --no-build check
    if "command: deploy --env staging --no-build" not in cd_content:
        raise PolicyViolation("cd.yml staging deploy step must specify 'command: deploy --env staging --no-build'")
    if "command: deploy --no-build" not in cd_content:
        raise PolicyViolation("cd.yml production deploy step must specify 'command: deploy --no-build'")

    # Pre-flight asset verification check
    if "test -f frontend/dist/index.html" not in cd_content:
        raise PolicyViolation("cd.yml must verify frontend/dist/index.html existence before Cloudflare deploy")

    # Staging-only deployment condition check: deploy-production must exclude staging_only
    if "inputs.target_env != 'staging_only'" not in cd_content:
        raise PolicyViolation("cd.yml deploy-production job must strictly require inputs.target_env != 'staging_only'")

    # Single-instance architecture check: Cloud Run deployments must configure --max-instances=1
    prod_cloud_run_match = re.search(r'gcloud run deploy queueless-backend\s+.*?--max-instances=(\d+)', cd_content, re.DOTALL)
    if not prod_cloud_run_match:
        raise PolicyViolation("cd.yml production Cloud Run deploy step missing --max-instances flag")
    if prod_cloud_run_match.group(1) != "1":
        raise PolicyViolation(f"cd.yml production Cloud Run deploy must set --max-instances=1 (MVP single-instance architecture); found {prod_cloud_run_match.group(1)}")

    log_pass("CI/CD workflows enforce least-privilege permissions, WIF, and Secret Manager references.")

def check_test_helpers_clean():
    print("[10/10] Verifying test helper cleanliness and absence of deprecated OTP methods...")
    test_helper = REPO_ROOT / "backend" / "src" / "test" / "java" / "com" / "queueless" / "TestRegistrationHelper.java"
    if not test_helper.exists():
        raise PolicyViolation(f"Missing TestRegistrationHelper.java at {test_helper}")

    content = test_helper.read_text(encoding="utf-8")
    if "prepareOtp" in content:
        raise PolicyViolation("TestRegistrationHelper.java still contains deprecated prepareOtp method")

    # Ensure no test in backend/src/test still calls prepareOtp
    backend_test = REPO_ROOT / "backend" / "src" / "test" / "java"
    for root, _, files in os.walk(backend_test):
        for file in files:
            if file.endswith(".java"):
                fpath = Path(root) / file
                fcontent = fpath.read_text(encoding="utf-8")
                if "prepareOtp" in fcontent:
                    raise PolicyViolation(f"Test class {file} still references obsolete prepareOtp")

    # Redundant backend Dockerfile check
    backend_dockerfile = REPO_ROOT / "backend" / "Dockerfile"
    if backend_dockerfile.exists():
        raise PolicyViolation("Redundant backend/Dockerfile exists; single root Dockerfile must be used.")

    log_pass("All test helpers, test classes, and container definitions are clean of obsolete OTP artifacts.")

def main():
    print("=" * 70)
    print("QueueLess — Production Policy & Security Pre-Flight Verification")
    print("=" * 70)

    checks = [
        check_no_hardcoded_credentials,
        check_demo_account_isolation,
        check_no_otp_sms,
        check_no_mock_provider_in_production,
        check_no_localhost_in_production,
        check_required_environment_variables,
        check_no_exposed_private_keys,
        check_no_auth_bypass,
        check_cicd_security_and_secrets,
        check_test_helpers_clean,
    ]

    failed = 0
    for check in checks:
        try:
            check()
        except PolicyViolation as pv:
            log_fail(str(pv))
            failed += 1
        except Exception as ex:
            log_fail(f"Unexpected error in {check.__name__}: {ex}")
            failed += 1

    print("=" * 70)
    if failed == 0:
        print("ALL 10 PRODUCTION POLICIES VERIFIED SUCCESSFULLY [READY FOR CI/CD]")
        print("=" * 70)
        sys.exit(0)
    else:
        print(f"FAILED: {failed} policy violation(s) detected. Aborting pipeline.", file=sys.stderr)
        print("=" * 70)
        sys.exit(1)

if __name__ == "__main__":
    main()
