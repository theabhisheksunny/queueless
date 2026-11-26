-- =============================================================================
-- QueueLess Production Database Identity & Least-Privilege Role Provisioning
-- File: scripts/provision_prod_db_roles.sql
--
-- DO NOT EXECUTE AUTOMATICALLY. Must be reviewed and executed manually in the
-- Supabase Production SQL Editor.
--
-- Execution Identity Requirements & Boundaries:
-- 1. Recommended Principal: supabase_admin (PostgreSQL Superuser).
--    - As a superuser, supabase_admin can unconditionally alter table and sequence
--      ownership regardless of pre-existing object owner.
-- 2. Non-Superuser Execution (postgres in Supabase):
--    - Permitted ONLY if existing tables are owned by postgres. Non-superusers
--      cannot alter ownership of objects they do not own.
--    - PostgreSQL 16 Role Membership Constraints:
--      * Executing ALTER TABLE ... OWNER TO <role> requires the caller to hold membership
--        in <role> with SET enabled.
--      * Executing GRANT <role> TO <caller> requires the caller to hold ADMIN OPTION on <role>.
--      * When <role> is created by supabase_admin, postgres receives ZERO membership and NO ADMIN OPTION.
--      * If postgres lacks ADMIN OPTION on an existing migrator role, it CANNOT grant membership
--        to itself and cannot transfer ownership.
--    - Explicit Preflight Authority Assertions:
--      * Block 0 verifies role creation and grant authority BEFORE ANY database modifications.
--      * Step 5 verifies ADMIN OPTION or active SET membership BEFORE ANY ownership modifications.
--      * If required authority is absent, the script immediately aborts with instructions to use supabase_admin.
--    - Temporary membership is explicitly established with SET enabled and strictly revoked
--      and verified post-transfer. Equivalent checks are enforced during rollback.
-- =============================================================================

-- 0. Pre-Flight Safeguards: Prevent Execution with Placeholder Passwords & Verify Caller
DO $$
DECLARE
    migrator_pass TEXT := 'REPLACE_WITH_STRONG_PRODUCTION_MIGRATOR_PASSWORD';
    app_pass TEXT := 'REPLACE_WITH_STRONG_PRODUCTION_APP_PASSWORD';
    is_admin BOOLEAN;
    is_super BOOLEAN;
    has_membership BOOLEAN := FALSE;
    has_set_membership BOOLEAN := FALSE;
    has_admin_option BOOLEAN := FALSE;
BEGIN
    -- Safeguard 1: Abort if placeholder passwords have not been customized
    IF migrator_pass LIKE 'REPLACE_WITH_%' OR app_pass LIKE 'REPLACE_WITH_%' THEN
        RAISE EXCEPTION 'SECURITY SAFEGUARD ABORT: Replace placeholder passwords in scripts/provision_prod_db_roles.sql before executing!';
    END IF;

    -- Safeguard 2: Verify executing role has administrative privileges
    SELECT rolsuper OR rolname IN ('postgres', 'supabase_admin')
    INTO is_admin
    FROM pg_roles
    WHERE rolname = CURRENT_USER;

    IF NOT COALESCE(is_admin, FALSE) THEN
        RAISE EXCEPTION 'FATAL: Script must be executed by an administrative role (supabase_admin or postgres). Current user: %', CURRENT_USER;
    END IF;

    -- Safeguard 3: Verify PostgreSQL 16 Role Membership Authority BEFORE ANY Modifications
    -- In PostgreSQL 16+, executing ALTER ... OWNER TO requires the caller to be a member of the target role with SET enabled.
    -- If caller is a non-superuser and queueless_migrator_prod already exists, caller MUST hold ADMIN OPTION or SET membership.
    -- ADMIN OPTION and SET capability are independent privileges. Neither implies the other.
    -- If authority is absent, abort immediately before Step 1 to prevent partial execution.
    SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = CURRENT_USER;
    IF NOT is_super THEN
        IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_migrator_prod') THEN
            has_membership := FALSE;
            has_admin_option := FALSE;
            has_set_membership := FALSE;

            SELECT
                TRUE,
                COALESCE(am.admin_option, FALSE),
                CASE
                    WHEN (to_jsonb(am) ? 'set_option') THEN
                        COALESCE((to_jsonb(am)->>'set_option')::boolean, FALSE)
                    WHEN current_setting('server_version_num')::integer < 160000 THEN
                        TRUE
                    ELSE
                        FALSE
                END
            INTO has_membership, has_admin_option, has_set_membership
            FROM pg_auth_members am
            JOIN pg_roles r ON r.oid = am.roleid
            JOIN pg_roles m ON m.oid = am.member
            WHERE r.rolname = 'queueless_migrator_prod'
              AND m.rolname = CURRENT_USER;

            has_membership := COALESCE(has_membership, FALSE);
            has_admin_option := COALESCE(has_admin_option, FALSE);
            has_set_membership := COALESCE(has_set_membership, FALSE);

            -- PostgreSQL 16 Independent SET vs ADMIN OPTION validation:
            -- 1. has_set_membership: Caller already possesses usable SET capability on target role.
            -- 2. has_admin_option: Caller holds ADMIN OPTION to grant temporary SET membership to itself.
            -- If caller possesses neither, caller cannot assume or grant this role.
            IF NOT has_set_membership AND NOT has_admin_option THEN
                RAISE EXCEPTION 'PREFLIGHT SAFEGUARD ABORT: Role [queueless_migrator_prod] already exists, but caller [%] is a non-superuser lacking both active SET membership and ADMIN OPTION! Non-superusers cannot grant or assume this role. Execute this script as supabase_admin.', CURRENT_USER;
            END IF;
        ELSE
            -- Role does not exist yet. Non-superuser must have CREATEROLE to create it.
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = CURRENT_USER AND rolcreaterole = TRUE) THEN
                RAISE EXCEPTION 'PREFLIGHT SAFEGUARD ABORT: Caller [%] is a non-superuser without CREATEROLE! Cannot create migrator/app roles. Execute this script as supabase_admin.', CURRENT_USER;
            END IF;
        END IF;
    END IF;

    RAISE NOTICE 'Pre-flight caller and role-authority checks passed for user: %', CURRENT_USER;
END
$$;

-- 1. Create Dedicated Migration Identity
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_migrator_prod') THEN
        CREATE ROLE queueless_migrator_prod WITH
            LOGIN
            NOSUPERUSER
            NOCREATEROLE
            NOCREATEDB
            NOREPLICATION
            NOBYPASSRLS
            NOINHERIT
            PASSWORD 'REPLACE_WITH_STRONG_PRODUCTION_MIGRATOR_PASSWORD';
        RAISE NOTICE 'Role queueless_migrator_prod created.';
    ELSE
        RAISE NOTICE 'Role queueless_migrator_prod already exists.';
    END IF;
END
$$;

-- 2. Create Dedicated Application Runtime Identity
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_app_prod') THEN
        CREATE ROLE queueless_app_prod WITH
            LOGIN
            NOSUPERUSER
            NOCREATEROLE
            NOCREATEDB
            NOREPLICATION
            NOBYPASSRLS
            NOINHERIT
            PASSWORD 'REPLACE_WITH_STRONG_PRODUCTION_APP_PASSWORD';
        RAISE NOTICE 'Role queueless_app_prod created.';
    ELSE
        RAISE NOTICE 'Role queueless_app_prod already exists.';
    END IF;
END
$$;

-- 3. Schema & DDL Privileges for Migrator Role
GRANT USAGE, CREATE ON SCHEMA public TO queueless_migrator_prod;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO queueless_migrator_prod;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO queueless_migrator_prod;

-- Ensure future tables and sequences created by migrator are accessible
ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_prod IN SCHEMA public
    GRANT ALL PRIVILEGES ON TABLES TO queueless_migrator_prod;
ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_prod IN SCHEMA public
    GRANT ALL PRIVILEGES ON SEQUENCES TO queueless_migrator_prod;

-- 4. Schema & DML Privileges for Application Runtime Role (Least Privilege)
GRANT USAGE ON SCHEMA public TO queueless_app_prod;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO queueless_app_prod;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO queueless_app_prod;

-- Automatically grant DML on future tables created by the migration role
ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_prod IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO queueless_app_prod;
ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_prod IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO queueless_app_prod;

-- 5. Explicit Allowlist Table & Sequence Ownership Transfer
-- Flyway requires table ownership to execute ALTER TABLE / DROP CONSTRAINT / CREATE INDEX.
-- Preflight validation verifies all preconditions BEFORE any object modification is made.
DO $$
DECLARE
    tbl TEXT;
    current_tbl_owner TEXT;
    canary_owner TEXT;
    seq_rec RECORD;
    is_super BOOLEAN;
    caller_role TEXT := CURRENT_USER;
    temp_membership_granted BOOLEAN := FALSE;
    has_membership BOOLEAN := FALSE;
    has_set_membership BOOLEAN := FALSE;
    has_admin_option BOOLEAN := FALSE;
    allowlist_tables TEXT[] := ARRAY[
        'users',
        'shops',
        'subscriptions',
        'trial_eligibility_ledger',
        'queue_sessions',
        'queue_customers',
        'otp_records',
        'application_settings',
        'business_category_change_requests',
        'business_category_change_audits',
        'customer_push_subscriptions',
        'businesses',
        'shop_memberships',
        'subscription_seats',
        'payment_records',
        'user_sessions',
        'audit_logs',
        'device_registrations',
        'device_replacement_requests',
        'device_auth_challenges',
        'idempotency_keys',
        'flyway_schema_history',
        'sse_tickets'
    ];
BEGIN
    SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;

    -- 5a. PREFLIGHT VALIDATION: Verify ALL existing allowlisted objects can be legally transferred
    -- Ensures zero partial ownership modifications if any precondition fails.
    FOREACH tbl IN ARRAY allowlist_tables LOOP
        IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = tbl) THEN
            SELECT tableowner INTO current_tbl_owner
            FROM pg_tables
            WHERE schemaname = 'public' AND tablename = tbl;

            IF current_tbl_owner <> 'queueless_migrator_prod' THEN
                IF NOT is_super AND current_tbl_owner <> caller_role THEN
                    RAISE EXCEPTION 'PREFLIGHT ABORT: Table public.% is owned by [%], but caller is non-superuser [%]! Non-superusers cannot transfer objects they do not own. Execute this script as supabase_admin.', tbl, current_tbl_owner, caller_role;
                END IF;
            END IF;

            -- Check sequence ownership preconditions
            FOR seq_rec IN (
                SELECT s.relname AS seqname, r.rolname AS seqowner
                FROM pg_class s
                JOIN pg_depend d ON d.objid = s.oid
                JOIN pg_class t ON d.refobjid = t.oid
                JOIN pg_roles r ON s.relowner = r.oid
                WHERE s.relkind = 'S'
                  AND t.relname = tbl
                  AND t.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
            ) LOOP
                IF seq_rec.seqowner <> 'queueless_migrator_prod' THEN
                    IF NOT is_super AND seq_rec.seqowner <> caller_role THEN
                        RAISE EXCEPTION 'PREFLIGHT ABORT: Sequence public.% is owned by [%], but caller is non-superuser [%]! Execute this script as supabase_admin.', seq_rec.seqname, seq_rec.seqowner, caller_role;
                    END IF;
                END IF;
            END LOOP;
        END IF;
    END LOOP;

    -- Verify canary table ownership precondition
    IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = '_system_environment_canary') THEN
        SELECT tableowner INTO canary_owner
        FROM pg_tables
        WHERE schemaname = 'public' AND tablename = '_system_environment_canary';

        IF canary_owner IS DISTINCT FROM 'canary_admin' THEN
            RAISE EXCEPTION 'PREFLIGHT ABORT: public._system_environment_canary is owned by [%], expected [canary_admin]! Canary ownership must remain canary_admin.', canary_owner;
        END IF;
    END IF;

    -- Check role membership grant authority for non-superusers:
    -- In PostgreSQL 16+, executing ALTER ... OWNER TO queueless_migrator_prod requires that caller
    -- is a member of queueless_migrator_prod with SET enabled.
    -- ADMIN OPTION and SET capability are completely independent PostgreSQL 16 privileges.
    -- If caller lacks SET membership and lacks ADMIN OPTION, abort immediately!
    IF NOT is_super THEN
        has_membership := FALSE;
        has_admin_option := FALSE;
        has_set_membership := FALSE;

        SELECT
            TRUE,
            COALESCE(am.admin_option, FALSE),
            CASE
                WHEN (to_jsonb(am) ? 'set_option') THEN
                    COALESCE((to_jsonb(am)->>'set_option')::boolean, FALSE)
                WHEN current_setting('server_version_num')::integer < 160000 THEN
                    TRUE
                ELSE
                    FALSE
            END
        INTO has_membership, has_admin_option, has_set_membership
        FROM pg_auth_members am
        JOIN pg_roles r ON r.oid = am.roleid
        JOIN pg_roles m ON m.oid = am.member
        WHERE r.rolname = 'queueless_migrator_prod'
          AND m.rolname = caller_role;

        has_membership := COALESCE(has_membership, FALSE);
        has_admin_option := COALESCE(has_admin_option, FALSE);
        has_set_membership := COALESCE(has_set_membership, FALSE);

        IF NOT has_set_membership AND NOT has_admin_option THEN
            RAISE EXCEPTION 'PREFLIGHT ABORT: Caller [%] is a non-superuser lacking ADMIN OPTION on [queueless_migrator_prod] and does not possess active membership with SET enabled! Cannot grant temporary membership for ownership transfer. Execute this script as supabase_admin.', caller_role;
        END IF;
    END IF;

    RAISE NOTICE 'Preflight validation PASSED: All ownership and role membership preconditions verified before modifying any objects.';

    -- 5b. ROLE MEMBERSHIP: PostgreSQL requires executing role to be a member of the new owner role
    -- with SET enabled to execute ALTER ... OWNER TO ... (unless executing as superuser).
    -- Crucial: Do NOT use generic pg_has_role(...) which treats membership with SET FALSE as sufficient.
    -- Strictly check IF NOT has_set_membership.
    IF NOT is_super THEN
        IF NOT has_set_membership THEN
            IF NOT has_admin_option THEN
                RAISE EXCEPTION 'PREFLIGHT ABORT: Caller [%] lacks SET membership and lacks ADMIN OPTION on [queueless_migrator_prod]! Cannot grant temporary SET membership. Execute this script as supabase_admin.', caller_role;
            END IF;
            IF current_setting('server_version_num')::integer >= 160000 THEN
                EXECUTE format('GRANT queueless_migrator_prod TO %I WITH SET TRUE', caller_role);
            ELSE
                EXECUTE format('GRANT queueless_migrator_prod TO %I', caller_role);
            END IF;
            temp_membership_granted := TRUE;
            RAISE NOTICE 'Temporarily granted queueless_migrator_prod to % with SET enabled for ownership transfer.', caller_role;
        END IF;
    END IF;

    -- 5c. TRANSFER OWNERSHIP
    FOREACH tbl IN ARRAY allowlist_tables LOOP
        IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = tbl) THEN
            SELECT tableowner INTO current_tbl_owner
            FROM pg_tables
            WHERE schemaname = 'public' AND tablename = tbl;

            IF current_tbl_owner = 'queueless_migrator_prod' THEN
                RAISE NOTICE 'Table public.% is already owned by queueless_migrator_prod.', tbl;
            ELSE
                EXECUTE format('ALTER TABLE public.%I OWNER TO queueless_migrator_prod', tbl);
                RAISE NOTICE 'Transferred ownership of table public.% from % to queueless_migrator_prod.', tbl, current_tbl_owner;

                -- Transfer associated sequences (SERIAL / BIGSERIAL dependencies)
                FOR seq_rec IN (
                    SELECT s.relname AS seqname, r.rolname AS seqowner
                    FROM pg_class s
                    JOIN pg_depend d ON d.objid = s.oid
                    JOIN pg_class t ON d.refobjid = t.oid
                    JOIN pg_roles r ON s.relowner = r.oid
                    WHERE s.relkind = 'S'
                      AND t.relname = tbl
                      AND t.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
                ) LOOP
                    IF seq_rec.seqowner <> 'queueless_migrator_prod' THEN
                        EXECUTE format('ALTER SEQUENCE public.%I OWNER TO queueless_migrator_prod', seq_rec.seqname);
                        RAISE NOTICE 'Transferred ownership of sequence public.% from % to queueless_migrator_prod.', seq_rec.seqname, seq_rec.seqowner;
                    END IF;
                END LOOP;
            END IF;
        ELSE
            IF tbl = 'otp_records' THEN
                RAISE NOTICE 'Legacy table public.otp_records is absent (expected: decommissioned in V2__drop_otp_records.sql).';
            ELSE
                RAISE NOTICE 'Allowlisted table public.% does not exist yet (migrator will create it with native ownership).', tbl;
            END IF;
        END IF;
    END LOOP;

    -- 5d. REVOKE TEMPORARY MEMBERSHIP (Restores least-privilege boundary)
    IF temp_membership_granted THEN
        EXECUTE format('REVOKE queueless_migrator_prod FROM %I', caller_role);
        RAISE NOTICE 'Revoked temporary queueless_migrator_prod membership from %.', caller_role;

        -- Verify revocation was effective: on PG 16+ active SET capability must be gone; on pre-PG16 membership must be gone
        IF current_setting('server_version_num')::integer >= 160000 THEN
            IF pg_has_role(caller_role, 'queueless_migrator_prod', 'SET') THEN
                RAISE EXCEPTION 'FATAL: Temporary membership revocation failed! Caller [%] still possesses active SET privilege on queueless_migrator_prod.', caller_role;
            END IF;
        ELSE
            IF pg_has_role(caller_role, 'queueless_migrator_prod', 'MEMBER') THEN
                RAISE EXCEPTION 'FATAL: Temporary membership revocation failed! Caller [%] is still a member of queueless_migrator_prod.', caller_role;
            END IF;
        END IF;
        RAISE NOTICE 'Verified temporary membership revocation: % no longer possesses active SET privilege on queueless_migrator_prod.', caller_role;
    END IF;

    -- 5e. POST-TRANSFER ASSERTION: Verify every existing allowlisted table is owned by migrator
    FOREACH tbl IN ARRAY allowlist_tables LOOP
        IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = tbl) THEN
            SELECT tableowner INTO current_tbl_owner
            FROM pg_tables
            WHERE schemaname = 'public' AND tablename = tbl;

            IF current_tbl_owner <> 'queueless_migrator_prod' THEN
                RAISE EXCEPTION 'FATAL POST-CHECK: Table public.% ownership transfer failed! Current owner: %, expected queueless_migrator_prod.', tbl, current_tbl_owner;
            END IF;
        END IF;
    END LOOP;

    RAISE NOTICE 'Allowlisted table ownership transfer completed and verified successfully.';
END
$$;

-- 6. Strict Canary Isolation (SELECT-ONLY; Zero Tamper Rights)
REVOKE ALL ON TABLE public._system_environment_canary FROM queueless_app_prod, queueless_migrator_prod;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE public._system_environment_canary FROM queueless_app_prod, queueless_migrator_prod;
GRANT SELECT ON TABLE public._system_environment_canary TO queueless_app_prod, queueless_migrator_prod;

-- 7. Ensure Canary Function Execution Privilege
GRANT EXECUTE ON FUNCTION public.protect_canary_table_immutability() TO queueless_app_prod, queueless_migrator_prod;

-- Ensure runtime and migrator roles are NOT members of canary_admin or postgres
REVOKE canary_admin FROM queueless_app_prod, queueless_migrator_prod;
REVOKE postgres FROM queueless_app_prod, queueless_migrator_prod;

-- 8. Final Isolation Assertions
DO $$
DECLARE
    auth_count INT;
BEGIN
    SELECT COUNT(*) INTO auth_count
    FROM pg_auth_members m
    JOIN pg_roles r ON m.member = r.oid
    JOIN pg_roles b ON m.roleid = b.oid
    WHERE r.rolname IN ('queueless_migrator_prod', 'queueless_app_prod')
      AND b.rolname IN ('canary_admin', 'postgres', 'supabase_admin');

    IF auth_count > 0 THEN
        RAISE EXCEPTION 'FATAL: Production roles possess prohibited administrative memberships (count=%)! Aborting.', auth_count;
    END IF;
    RAISE NOTICE 'Production role isolation verified: 0 administrative role memberships.';
END
$$;

-- =============================================================================
-- Verification Queries (Run after executing provisioning script)
-- =============================================================================

-- Query 1: Verify Role Flags (Must show rolsuper=f, rolcreaterole=f, rolbypassrls=f)
SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolcanlogin, rolreplication, rolbypassrls
FROM pg_roles
WHERE rolname IN ('queueless_migrator_prod', 'queueless_app_prod');

-- Query 2: Verify Canary Table Grants (Must show ONLY SELECT for both roles)
SELECT grantee, privilege_type, is_grantable
FROM information_schema.role_table_grants
WHERE table_schema = 'public' AND table_name = '_system_environment_canary'
  AND grantee IN ('queueless_migrator_prod', 'queueless_app_prod');

-- Query 3: Verify No Administrative Role Memberships
SELECT r.rolname AS member, b.rolname AS role
FROM pg_auth_members m
JOIN pg_roles r ON m.member = r.oid
JOIN pg_roles b ON m.roleid = b.oid
WHERE r.rolname IN ('queueless_migrator_prod', 'queueless_app_prod');

-- Query 4: Verify Table Ownership for QueueLess Application Tables
SELECT tablename, tableowner
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY tablename;

-- =============================================================================
-- Safe Rollback Guidance (Reverts ownership to postgres without broad DROP OWNED)
-- =============================================================================
-- Execution Identity Requirements:
-- Recommended Caller: supabase_admin (superuser).
-- If executed by postgres, postgres must be temporarily granted queueless_migrator_prod
-- to execute ALTER ... OWNER TO postgres, and revoked prior to dropping roles.
--
-- DO $$
-- DECLARE
--     tbl TEXT;
--     seq_rec RECORD;
--     unhandled_count INT;
--     unhandled_names TEXT;
--     is_super BOOLEAN;
--     caller_role TEXT := CURRENT_USER;
--     temp_membership_granted BOOLEAN := FALSE;
--     has_membership BOOLEAN := FALSE;
--     has_set_membership BOOLEAN := FALSE;
--     has_admin_option BOOLEAN := FALSE;
--     allowlist_tables TEXT[] := ARRAY[
--         'users', 'shops', 'subscriptions', 'trial_eligibility_ledger',
--         'queue_sessions', 'queue_customers', 'otp_records', 'application_settings',
--         'business_category_change_requests', 'business_category_change_audits',
--         'customer_push_subscriptions', 'businesses', 'shop_memberships',
--         'subscription_seats', 'payment_records', 'user_sessions', 'audit_logs',
--         'device_registrations', 'device_replacement_requests',
--         'device_auth_challenges', 'idempotency_keys', 'flyway_schema_history'
--     ];
-- BEGIN
--     SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;
--
--     -- 1. PRE-CHECK: Detect any unhandled objects owned by migrator outside allowlist
--     SELECT COUNT(*), COALESCE(string_agg(c.relname, ', '), 'NONE')
--     INTO unhandled_count, unhandled_names
--     FROM pg_class c
--     JOIN pg_roles r ON c.relowner = r.oid
--     JOIN pg_namespace n ON c.relnamespace = n.oid
--     WHERE n.nspname = 'public'
--       AND r.rolname = 'queueless_migrator_prod'
--       AND c.relkind IN ('r', 'S', 'v', 'm', 'p')
--       AND c.relname NOT IN (SELECT unnest(allowlist_tables))
--       AND NOT EXISTS (
--           SELECT 1 FROM pg_depend d
--           JOIN pg_class t ON d.refobjid = t.oid
--           WHERE d.objid = c.oid
--             AND t.relname IN (SELECT unnest(allowlist_tables))
--       );
--
--     IF unhandled_count > 0 THEN
--         RAISE EXCEPTION 'ROLLBACK ABORT: Found % object(s) owned by queueless_migrator_prod outside allowlist: [%]! Reassign these objects before dropping role.', unhandled_count, unhandled_names;
--     END IF;
--
--     -- 2. Verify and establish temporary membership if non-superuser to permit ALTER OWNER
--     IF NOT is_super THEN
--         has_membership := FALSE;
--         has_admin_option := FALSE;
--         has_set_membership := FALSE;
--
--         SELECT
--             TRUE,
--             COALESCE(am.admin_option, FALSE),
--             CASE
--                 WHEN (to_jsonb(am) ? 'set_option') THEN
--                     COALESCE((to_jsonb(am)->>'set_option')::boolean, FALSE)
--                 WHEN current_setting('server_version_num')::integer < 160000 THEN
--                     TRUE
--                 ELSE
--                     FALSE
--             END
--         INTO has_membership, has_admin_option, has_set_membership
--         FROM pg_auth_members am
--         JOIN pg_roles r ON r.oid = am.roleid
--         JOIN pg_roles m ON m.oid = am.member
--         WHERE r.rolname = 'queueless_migrator_prod'
--           AND m.rolname = caller_role;
--
--         has_membership := COALESCE(has_membership, FALSE);
--         has_admin_option := COALESCE(has_admin_option, FALSE);
--         has_set_membership := COALESCE(has_set_membership, FALSE);
--
--         IF NOT has_set_membership AND NOT has_admin_option THEN
--             RAISE EXCEPTION 'ROLLBACK ABORT: Caller [%] is a non-superuser lacking ADMIN OPTION on [queueless_migrator_prod] and does not possess active membership with SET enabled! Cannot restore table ownership. Execute rollback as supabase_admin.', caller_role;
--         END IF;
--
--         IF NOT has_set_membership THEN
--             IF NOT has_admin_option THEN
--                 RAISE EXCEPTION 'ROLLBACK ABORT: Caller [%] lacks SET membership and lacks ADMIN OPTION on [queueless_migrator_prod]! Cannot grant temporary membership for rollback. Execute rollback as supabase_admin.', caller_role;
--             END IF;
--         IF current_setting('server_version_num')::integer >= 160000 THEN
--             EXECUTE format('GRANT queueless_migrator_prod TO %I WITH SET TRUE', caller_role);
--         ELSE
--             EXECUTE format('GRANT queueless_migrator_prod TO %I', caller_role);
--         END IF;
--             temp_membership_granted := TRUE;
--             RAISE NOTICE 'Temporarily granted queueless_migrator_prod to % with SET enabled for rollback.', caller_role;
--         END IF;
--     END IF;
--
--     -- 3. Restore table and sequence ownership back to postgres
--     FOREACH tbl IN ARRAY allowlist_tables LOOP
--         IF EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename = tbl) THEN
--             EXECUTE format('ALTER TABLE public.%I OWNER TO postgres', tbl);
--             FOR seq_rec IN (
--                 SELECT s.relname AS seqname
--                 FROM pg_class s
--                 JOIN pg_depend d ON d.objid = s.oid
--                 JOIN pg_class t ON d.refobjid = t.oid
--                 WHERE s.relkind = 'S' AND t.relname = tbl
--                   AND t.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
--             ) LOOP
--                 EXECUTE format('ALTER SEQUENCE public.%I OWNER TO postgres', seq_rec.seqname);
--             END LOOP;
--         END IF;
--     END LOOP;
--
--     -- 4. Revoke temporary membership and verify revocation
--     IF temp_membership_granted THEN
--         EXECUTE format('REVOKE queueless_migrator_prod FROM %I', caller_role);
--         -- Verify revocation was effective: on PG 16+ active SET capability must be gone; on pre-PG16 membership must be gone
--         IF current_setting('server_version_num')::integer >= 160000 THEN
--             IF pg_has_role(caller_role, 'queueless_migrator_prod', 'SET') THEN
--                 RAISE EXCEPTION 'FATAL: Temporary membership revocation failed during rollback! Caller [%] still possesses active SET privilege on queueless_migrator_prod.', caller_role;
--             END IF;
--         ELSE
--             IF pg_has_role(caller_role, 'queueless_migrator_prod', 'MEMBER') THEN
--                 RAISE EXCEPTION 'FATAL: Temporary membership revocation failed during rollback! Caller [%] is still a member of queueless_migrator_prod.', caller_role;
--             END IF;
--         END IF;
--         RAISE NOTICE 'Verified temporary membership revocation during rollback: % no longer possesses active SET privilege on queueless_migrator_prod.', caller_role;
--     END IF;
--
--     -- 5. Revoke default privileges
--     ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_prod IN SCHEMA public
--         REVOKE ALL ON TABLES FROM queueless_migrator_prod, queueless_app_prod;
--     ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_prod IN SCHEMA public
--         REVOKE ALL ON SEQUENCES FROM queueless_migrator_prod, queueless_app_prod;
--
--     -- 6. Revoke schema privileges
--     REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM queueless_app_prod, queueless_migrator_prod;
--     REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM queueless_app_prod, queueless_migrator_prod;
--     REVOKE ALL PRIVILEGES ON FUNCTION public.protect_canary_table_immutability() FROM queueless_app_prod, queueless_migrator_prod;
--     REVOKE USAGE, CREATE ON SCHEMA public FROM queueless_migrator_prod;
--     REVOKE USAGE ON SCHEMA public FROM queueless_app_prod;
--
--     -- 7. Verify zero remaining objects owned by migrator or app roles before drop
--     SELECT COUNT(*) INTO unhandled_count
--     FROM pg_class c
--     JOIN pg_roles r ON c.relowner = r.oid
--     WHERE r.rolname IN ('queueless_migrator_prod', 'queueless_app_prod');
--
--     IF unhandled_count > 0 THEN
--         RAISE EXCEPTION 'FATAL: Production roles still own % object(s)! Role drop aborted.', unhandled_count;
--     END IF;
--
--     -- 8. Drop roles
--     DROP ROLE IF EXISTS queueless_app_prod;
--     DROP ROLE IF EXISTS queueless_migrator_prod;
--     RAISE NOTICE 'Production role provisioning safely rolled back; table ownership restored to postgres.';
-- END
-- $$;
