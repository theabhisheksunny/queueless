#!/usr/bin/env python3
"""
QueueLess — Deployment Security Remediation Verifier
File: scripts/verify_deployment_remediation.py

Verifies the critical pre-staging security remediations:
1. Database identity separation: application properties never default to 'postgres' runtime role.
2. Migration and runtime identities are distinct, fail closed without fallback.
3. Canary privilege isolation: revocation of canary_admin membership, search_path pinned, no exception suppression.
4. Role provisioning scripts: explicit allowlist table ownership transfer, canary preservation, placeholder safeguards, and safe rollback.
5. Cloud Run deployment: explicit Flyway secret injection (QUEUELESS_STAGING_MIGRATOR_USER/PASS, QUEUELESS_PROD_MIGRATOR_USER/PASS).
6. Dedicated GitHub service-account secrets have NO fallback to GCP_SERVICE_ACCOUNT.
7. Routine Cloud Run deployment commands do NOT include --allow-unauthenticated.
"""

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent


class RemediationCheckError(Exception):
    pass


def log_pass(msg: str):
    print(f"  [PASS] {msg}")


def log_fail(msg: str):
    print(f"  [FAIL] {msg}", file=sys.stderr)


def check_database_identity_separation():
    print("[1/7] Verifying database runtime username defaults...")
    stg_props = (REPO_ROOT / "backend/src/main/resources/application-staging.properties").read_text(encoding="utf-8")
    prod_props = (REPO_ROOT / "backend/src/main/resources/application-prod.properties").read_text(encoding="utf-8")

    # Ensure no ':postgres' default for datasource username
    if re.search(r"spring\.datasource\.username\s*=\s*\$\{[^}]+:postgres\}", stg_props):
        raise RemediationCheckError(
            "application-staging.properties still defaults spring.datasource.username to 'postgres'!"
        )
    if re.search(r"spring\.datasource\.username\s*=\s*\$\{[^}]+:postgres\}", prod_props):
        raise RemediationCheckError(
            "application-prod.properties still defaults spring.datasource.username to 'postgres'!"
        )

    # Verify dedicated non-postgres runtime usernames
    if "queueless_app_staging" not in stg_props:
        raise RemediationCheckError(
            "application-staging.properties does not reference dedicated runtime role 'queueless_app_staging'!"
        )
    if "queueless_app_prod" not in prod_props:
        raise RemediationCheckError(
            "application-prod.properties does not reference dedicated runtime role 'queueless_app_prod'!"
        )

    log_pass("Staging and production configurations never default to 'postgres' and declare dedicated runtime roles.")


def check_migration_identity_distinct():
    print("[2/7] Verifying distinct migration identity configuration and fail-closed properties...")
    stg_props = (REPO_ROOT / "backend/src/main/resources/application-staging.properties").read_text(encoding="utf-8")
    prod_props = (REPO_ROOT / "backend/src/main/resources/application-prod.properties").read_text(encoding="utf-8")

    for name, props in [("staging", stg_props), ("production", prod_props)]:
        # Flyway user must NOT fall back to spring.datasource.username
        if "spring.flyway.user=${SPRING_FLYWAY_USER:${spring.datasource.username}}" in props:
            raise RemediationCheckError(
                f"application-{name}.properties still has silent fallback to spring.datasource.username!"
            )
        if "spring.flyway.password=${SPRING_FLYWAY_PASSWORD:${spring.datasource.password}}" in props:
            raise RemediationCheckError(
                f"application-{name}.properties still has silent fallback to spring.datasource.password!"
            )

        # Must strictly require SPRING_FLYWAY_USER and SPRING_FLYWAY_PASSWORD
        if "spring.flyway.user=${SPRING_FLYWAY_USER}" not in props:
            raise RemediationCheckError(
                f"application-{name}.properties must declare 'spring.flyway.user=${{SPRING_FLYWAY_USER}}'!"
            )
        if "spring.flyway.password=${SPRING_FLYWAY_PASSWORD}" not in props:
            raise RemediationCheckError(
                f"application-{name}.properties must declare 'spring.flyway.password=${{SPRING_FLYWAY_PASSWORD}}'!"
            )

    log_pass("Flyway migration credentials require explicit injection without silent fallback to runtime credentials.")


def check_canary_privilege_isolation():
    print("[3/7] Verifying canary privilege isolation and membership revocation assertions...")
    canary_sql = (REPO_ROOT / "scripts/bootstrap_database_canary.sql").read_text(encoding="utf-8")

    # SECURITY DEFINER must NOT be present on the immutability function
    fn_match = re.search(
        r"CREATE OR REPLACE FUNCTION public\.protect_canary_table_immutability\(\).*?AS \$\$", canary_sql, re.DOTALL
    )
    if not fn_match:
        raise RemediationCheckError(
            "Could not locate protect_canary_table_immutability function definition in bootstrap_database_canary.sql!"
        )
    fn_def = fn_match.group(0)

    if "SECURITY DEFINER" in fn_def:
        raise RemediationCheckError(
            "protect_canary_table_immutability() still contains unsafe SECURITY DEFINER declaration!"
        )

    if "SET search_path = pg_catalog, pg_temp" not in fn_def and "SET search_path = pg_catalog" not in fn_def:
        raise RemediationCheckError(
            "protect_canary_table_immutability() does not pin search_path to pg_catalog, pg_temp!"
        )

    # Verify ON CONFLICT DO NOTHING to prevent silent overwrite
    if "ON CONFLICT (id) DO UPDATE SET" in canary_sql:
        raise RemediationCheckError(
            "bootstrap_database_canary.sql still contains ON CONFLICT DO UPDATE SET (risk of silent canary overwrite)!"
        )
    if "ON CONFLICT (id) DO NOTHING" not in canary_sql:
        raise RemediationCheckError(
            "bootstrap_database_canary.sql must use ON CONFLICT (id) DO NOTHING for safe rerun!"
        )

    # Revocation of canary_admin membership and ADMIN OPTION must be present
    if "REVOKE ADMIN OPTION FOR canary_admin" not in canary_sql:
        raise RemediationCheckError(
            "bootstrap_database_canary.sql missing explicit REVOKE ADMIN OPTION FOR canary_admin!"
        )
    if "REVOKE canary_admin FROM" not in canary_sql:
        raise RemediationCheckError(
            "bootstrap_database_canary.sql missing explicit REVOKE canary_admin FROM executing user!"
        )

    # Verify no exception suppression around revocation
    if re.search(r"REVOKE.*?canary_admin.*?EXCEPTION\s+WHEN\s+OTHERS", canary_sql, re.DOTALL):
        raise RemediationCheckError(
            "bootstrap_database_canary.sql suppresses revocation errors via EXCEPTION WHEN OTHERS!"
        )

    # Explicit assertion against pg_auth_members
    if "FROM pg_auth_members" not in canary_sql or "r.rolname = 'canary_admin'" not in canary_sql:
        raise RemediationCheckError(
            "bootstrap_database_canary.sql missing explicit post-condition assertion querying pg_auth_members!"
        )

    log_pass(
        "Canary script eliminates SECURITY DEFINER, revokes canary_admin membership/admin option, and asserts 0 members in pg_auth_members."
    )


def check_role_provisioning_scripts():
    print("[4/7] Verifying database role provisioning scripts, explicit allowlist, and safe rollback...")
    stg_script = REPO_ROOT / "scripts/provision_staging_db_roles.sql"
    prod_script = REPO_ROOT / "scripts/provision_prod_db_roles.sql"

    if not stg_script.exists():
        raise RemediationCheckError("Missing scripts/provision_staging_db_roles.sql!")
    if not prod_script.exists():
        raise RemediationCheckError("Missing scripts/provision_prod_db_roles.sql!")

    stg_content = stg_script.read_text(encoding="utf-8")
    prod_content = prod_script.read_text(encoding="utf-8")

    allowlist_tables = [
        "users",
        "shops",
        "subscriptions",
        "trial_eligibility_ledger",
        "queue_sessions",
        "queue_customers",
        "application_settings",
        "business_category_change_requests",
        "business_category_change_audits",
        "customer_push_subscriptions",
        "businesses",
        "shop_memberships",
        "subscription_seats",
        "payment_records",
        "user_sessions",
        "audit_logs",
        "device_registrations",
        "device_replacement_requests",
        "device_auth_challenges",
        "idempotency_keys",
        "flyway_schema_history",
    ]

    for name, content in [("staging", stg_content), ("production", prod_content)]:
        for forbidden in ["SUPERUSER", "CREATEROLE", "BYPASSRLS"]:
            if f"NO{forbidden}" not in content:
                raise RemediationCheckError(f"{name} role script does not explicitly declare NO{forbidden}!")
        if "REVOKE canary_admin" not in content:
            raise RemediationCheckError(f"{name} role script does not explicitly REVOKE canary_admin from app roles!")
        if "REVOKE INSERT, UPDATE, DELETE, TRUNCATE" not in content:
            raise RemediationCheckError(f"{name} role script does not revoke DML/TRUNCATE on canary table!")

        # Verify explicit table allowlist
        for tbl in allowlist_tables:
            if f"'{tbl}'" not in content:
                raise RemediationCheckError(f"{name} role script missing allowlisted application table '{tbl}'!")

        # Verify executing role check
        if "CURRENT_USER" not in content or "postgres" not in content:
            raise RemediationCheckError(
                f"{name} role script does not verify executing role is postgres or supabase_admin!"
            )

        # Verify placeholder password safeguard
        if "REPLACE_WITH_" not in content or "SECURITY SAFEGUARD" not in content:
            raise RemediationCheckError(f"{name} role script missing placeholder password execution safeguard!")

        # Verify canary table protection
        if "_system_environment_canary" not in content or "canary_admin" not in content:
            raise RemediationCheckError(f"{name} role script does not verify canary ownership remains canary_admin!")

        # Verify safe rollback restores ownership to postgres without blanket DROP OWNED
        if "ALTER TABLE public.%I OWNER TO postgres" not in content:
            raise RemediationCheckError(
                f"{name} rollback guidance must explicitly restore allowlisted table ownership to postgres!"
            )

    log_pass(
        "Role provisioning scripts enforce explicit allowlist ownership transfer, caller verification, canary protection, and safe rollback."
    )


def check_cloud_run_flyway_secret_injection():
    print("[5/7] Verifying Cloud Run Flyway migration secret injection in cd.yml...")
    cd_yaml = (REPO_ROOT / ".github/workflows/cd.yml").read_text(encoding="utf-8")

    # Staging deployment secrets
    if "SPRING_FLYWAY_USER=QUEUELESS_STAGING_MIGRATOR_USER:latest" not in cd_yaml:
        raise RemediationCheckError(
            "cd.yml staging deployment missing SPRING_FLYWAY_USER=QUEUELESS_STAGING_MIGRATOR_USER:latest mapping!"
        )
    if "SPRING_FLYWAY_PASSWORD=QUEUELESS_STAGING_MIGRATOR_PASS:latest" not in cd_yaml:
        raise RemediationCheckError(
            "cd.yml staging deployment missing SPRING_FLYWAY_PASSWORD=QUEUELESS_STAGING_MIGRATOR_PASS:latest mapping!"
        )

    # Production deployment secrets
    if "SPRING_FLYWAY_USER=QUEUELESS_PROD_MIGRATOR_USER:latest" not in cd_yaml:
        raise RemediationCheckError(
            "cd.yml production deployment missing SPRING_FLYWAY_USER=QUEUELESS_PROD_MIGRATOR_USER:latest mapping!"
        )
    if "SPRING_FLYWAY_PASSWORD=QUEUELESS_PROD_MIGRATOR_PASS:latest" not in cd_yaml:
        raise RemediationCheckError(
            "cd.yml production deployment missing SPRING_FLYWAY_PASSWORD=QUEUELESS_PROD_MIGRATOR_PASS:latest mapping!"
        )

    log_pass(
        "Cloud Run staging and production deployments explicitly inject dedicated Flyway migration secrets via Secret Manager."
    )


def check_github_service_account_fallbacks():
    print("[6/7] Verifying elimination of shared GCP_SERVICE_ACCOUNT fallbacks...")
    cd_yaml = (REPO_ROOT / ".github/workflows/cd.yml").read_text(encoding="utf-8")

    if "GCP_SERVICE_ACCOUNT" in cd_yaml:
        raise RemediationCheckError(
            ".github/workflows/cd.yml still contains references to shared fallback secret 'GCP_SERVICE_ACCOUNT'!"
        )

    # Check that dedicated SA secrets are explicitly required
    required_secrets = ["GCP_STAGING_DEPLOYER_SA", "GCP_PROMOTER_SA", "GCP_PROD_DEPLOYER_SA"]
    for sa in required_secrets:
        if sa not in cd_yaml:
            raise RemediationCheckError(
                f".github/workflows/cd.yml does not reference dedicated service account secret '{sa}'!"
            )

    log_pass(
        "All shared service account fallbacks eliminated; dedicated deployer and promoter secrets strictly required."
    )


def check_cloud_run_routine_iam():
    print("[7/7] Verifying Cloud Run routine deployment IAM design...")
    cd_yaml = (REPO_ROOT / ".github/workflows/cd.yml").read_text(encoding="utf-8")

    # Routine deployments must NOT execute --allow-unauthenticated
    if "--allow-unauthenticated" in cd_yaml:
        raise RemediationCheckError(
            ".github/workflows/cd.yml still contains '--allow-unauthenticated' in routine deployment commands!"
        )

    # Ensure no dummy fallbacks for project ref
    if "queueless-staging-ref" in cd_yaml or "queueless-prod-ref" in cd_yaml:
        raise RemediationCheckError(
            ".github/workflows/cd.yml still contains insecure dummy fallbacks ('queueless-staging-ref'/'queueless-prod-ref')!"
        )

    log_pass("Routine deployment commands removed --allow-unauthenticated, avoiding unauthorized setIamPolicy calls.")


def main():
    print("=" * 70)
    print("QueueLess — Deployment Security Remediation Verification Suite")
    print("=" * 70)

    checks = [
        check_database_identity_separation,
        check_migration_identity_distinct,
        check_canary_privilege_isolation,
        check_role_provisioning_scripts,
        check_cloud_run_flyway_secret_injection,
        check_github_service_account_fallbacks,
        check_cloud_run_routine_iam,
    ]

    failed = 0
    for check in checks:
        try:
            check()
        except RemediationCheckError as e:
            log_fail(str(e))
            failed += 1
        except Exception as ex:
            log_fail(f"Unexpected error in {check.__name__}: {ex}")
            failed += 1

    print("=" * 70)
    if failed == 0:
        print("ALL 7 SECURITY REMEDIATIONS VERIFIED SUCCESSFULLY")
        print("=" * 70)
        sys.exit(0)
    else:
        print(f"FAILED: {failed} check(s) failed. Remediate issues before deployment.", file=sys.stderr)
        print("=" * 70)
        sys.exit(1)


if __name__ == "__main__":
    main()
