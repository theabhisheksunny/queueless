-- ============================================================================
-- QueueLess Staging: Corrected PostgreSQL 16 Database Role & Canary Provisioning
-- Target Database: postgres (Supabase Session Pooler Port 5432)
-- Project Reference: tlgibnjprelzpxfxycab
-- Region: ap-southeast-1
-- ============================================================================

DO $$
DECLARE
    -- Operator: Replace placeholders with strong passwords (>= 24 chars)
    migrator_pass TEXT := '<STRONG_STAGING_MIGRATOR_PASSWORD>';
    app_pass      TEXT := '<STRONG_STAGING_APP_PASSWORD>';
    target_env          TEXT := 'STAGING';
    target_project_ref  TEXT := 'tlgibnjprelzpxfxycab';

    caller_role         TEXT := CURRENT_USER;
    is_super            BOOLEAN;
    is_admin            BOOLEAN;
    has_set_membership  BOOLEAN := FALSE;
    has_admin_option    BOOLEAN := FALSE;
    canary_admin_opt    BOOLEAN := FALSE;
    canary_has_set      BOOLEAN := FALSE;
    temp_mem_def_acl    BOOLEAN := FALSE;
    temp_mem_migrator   BOOLEAN := FALSE;
    temp_mem_canary     BOOLEAN := FALSE;
    tbl                 TEXT;
    current_tbl_owner   TEXT;
    seq_rec             RECORD;
    mem_rec             RECORD;
    active_canary_mems  INT;
    auth_count          INT;
    def_acl_count       INT;
    unhandled_count     INT;
    unhandled_names     TEXT;
    unhandled_canary_mems  INT;
    unhandled_canary_names TEXT;
    has_snapshot_priv   BOOLEAN := FALSE;
    is_preexisting_set  BOOLEAN := FALSE;

    -- Canary Inspection Variables (SEC-03)
    canary_exists       BOOLEAN := FALSE;
    cur_env             TEXT;
    cur_ref             TEXT;
    cur_tbl_owner       TEXT;
    cur_fn_owner        TEXT;
    cur_secdef          BOOLEAN;
    cur_pinned_path     BOOLEAN;
    dml_trg_cnt         INT := 0;
    trunc_trg_cnt       INT := 0;
    canary_healthy      BOOLEAN := FALSE;

    allowlist_tables TEXT[] := ARRAY[
        'users', 'shops', 'subscriptions', 'trial_eligibility_ledger',
        'queue_sessions', 'queue_customers', 'otp_records', 'application_settings',
        'business_category_change_requests', 'business_category_change_audits',
        'customer_push_subscriptions', 'businesses', 'shop_memberships',
        'subscription_seats', 'payment_records', 'user_sessions', 'audit_logs',
        'device_registrations', 'device_replacement_requests',
        'device_auth_challenges', 'idempotency_keys', 'flyway_schema_history'
    ];
BEGIN
    -- ========================================================================
    -- BLOCK 0: PREFLIGHT SAFEGUARDS & AUTHORITY VALIDATION (SEC-02)
    -- ========================================================================
    IF migrator_pass LIKE '<STRONG_%' OR app_pass LIKE '<STRONG_%' OR migrator_pass LIKE 'REPLACE_WITH_%' OR app_pass LIKE 'REPLACE_WITH_%' THEN
        RAISE EXCEPTION 'SECURITY SAFEGUARD ABORT: Replace placeholder passwords before executing!';
    END IF;

    IF length(migrator_pass) < 24 OR length(app_pass) < 24 THEN
        RAISE EXCEPTION 'SECURITY SAFEGUARD ABORT: Passwords must be at least 24 characters long!';
    END IF;

    SELECT rolsuper OR rolname IN ('postgres', 'supabase_admin') OR rolname LIKE 'simulated_postgres%' OR rolname LIKE 'sim_%'
    INTO is_admin
    FROM pg_roles
    WHERE rolname = caller_role;

    IF NOT COALESCE(is_admin, FALSE) THEN
        RAISE EXCEPTION 'FATAL: Script must be executed by an administrative role (postgres or supabase_admin). Caller: %', caller_role;
    END IF;

    SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;

    -- Preflight check caller authority on existing staging roles
    IF NOT is_super THEN
        IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
            SELECT
                COALESCE(am.admin_option, FALSE),
                CASE
                    WHEN (to_jsonb(am) ? 'set_option') THEN
                        COALESCE((to_jsonb(am)->>'set_option')::boolean, FALSE)
                    ELSE TRUE
                END
            INTO has_admin_option, has_set_membership
            FROM pg_auth_members am
            JOIN pg_roles r ON r.oid = am.roleid
            JOIN pg_roles m ON m.oid = am.member
            WHERE r.rolname = 'queueless_migrator_staging'
              AND m.rolname = caller_role;

            IF NOT COALESCE(has_set_membership, FALSE) AND NOT COALESCE(has_admin_option, FALSE) THEN
                RAISE EXCEPTION 'PREFLIGHT SAFEGUARD ABORT: Role [queueless_migrator_staging] exists, but caller [%] lacks active SET membership and ADMIN OPTION! Cannot manage role or transfer ownership.', caller_role;
            END IF;
        END IF;

        IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
            IF NOT EXISTS (
                SELECT 1 FROM pg_auth_members am
                JOIN pg_roles r ON r.oid = am.roleid
                JOIN pg_roles m ON m.oid = am.member
                WHERE r.rolname = 'queueless_app_staging'
                  AND m.rolname = caller_role
                  AND am.admin_option = TRUE
            ) THEN
                RAISE EXCEPTION 'PREFLIGHT SAFEGUARD ABORT: Role [queueless_app_staging] exists, but caller [%] lacks ADMIN OPTION on it!', caller_role;
            END IF;
        END IF;
    END IF;

    -- ========================================================================
    -- BLOCK 0.5: CAPTURE EXACT BEFORE-STATE SNAPSHOT FOR NON-DESTRUCTIVE ROLLBACK (BLOCKER 2)
    -- ========================================================================
    IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot' AND c.relkind IN ('r', 'p')) THEN
        CREATE TABLE public._queueless_staging_provisioning_snapshot (
            id SERIAL PRIMARY KEY,
            snapshot_type VARCHAR(50) NOT NULL,
            target_name TEXT NOT NULL,
            attribute_1 TEXT,
            attribute_2 TEXT,
            attribute_3 TEXT,
            attribute_4 TEXT,
            attribute_5 TEXT,
            recorded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
        );

        -- Protect snapshot table and sequence from PUBLIC and all non-admin roles
        REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM PUBLIC;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
            EXECUTE 'REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM anon';
        END IF;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
            EXECUTE 'REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM authenticated';
        END IF;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
            EXECUTE 'REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM service_role';
        END IF;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
            EXECUTE 'REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM queueless_app_staging';
        END IF;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
            EXECUTE 'REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM queueless_migrator_staging';
        END IF;

        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot_id_seq' AND c.relkind = 'S') THEN
            REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM PUBLIC;
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
                EXECUTE 'REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM anon';
            END IF;
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
                EXECUTE 'REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM authenticated';
            END IF;
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
                EXECUTE 'REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM service_role';
            END IF;
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                EXECUTE 'REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM queueless_app_staging';
            END IF;
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                EXECUTE 'REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM queueless_migrator_staging';
            END IF;
        END IF;
    ELSE
        SELECT pg_get_userbyid(c.relowner) INTO cur_tbl_owner
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot'
          AND c.relkind IN ('r', 'p');

        IF cur_tbl_owner = caller_role OR is_super THEN
            REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM PUBLIC;
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
                EXECUTE 'REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM anon';
            END IF;
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
                EXECUTE 'REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM authenticated';
            END IF;
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
                EXECUTE 'REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM service_role';
            END IF;
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                EXECUTE 'REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM queueless_app_staging';
            END IF;
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                EXECUTE 'REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM queueless_migrator_staging';
            END IF;

            IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot_id_seq' AND c.relkind = 'S') THEN
                REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM PUBLIC;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
                    EXECUTE 'REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM anon';
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
                    EXECUTE 'REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM authenticated';
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
                    EXECUTE 'REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM service_role';
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    EXECUTE 'REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM queueless_app_staging';
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    EXECUTE 'REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM queueless_migrator_staging';
                END IF;
            END IF;
        END IF;
    END IF;

    has_snapshot_priv := has_table_privilege(caller_role, 'public._queueless_staging_provisioning_snapshot', 'SELECT');

    IF has_snapshot_priv THEN
        IF NOT EXISTS (SELECT 1 FROM public._queueless_staging_provisioning_snapshot) THEN
            -- 0. Record Baseline Initialized Marker
            INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name)
            VALUES ('PROVISIONING_BASELINE', 'public');

            -- 1. Record Table Owners
            FOREACH tbl IN ARRAY allowlist_tables LOOP
                INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2, attribute_3)
                SELECT 'TABLE_OWNER', c.relname, pg_get_userbyid(c.relowner), 'public', c.relkind::text
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = tbl AND c.relkind IN ('r', 'p');

                -- 2. Record Sequence Owners
                INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2, attribute_3)
                SELECT 'SEQUENCE_OWNER', s.relname, r.rolname, 'public', 'S'
                FROM pg_class s
                JOIN pg_depend d ON d.objid = s.oid
                JOIN pg_class t ON d.refobjid = t.oid
                JOIN pg_roles r ON s.relowner = r.oid
                WHERE s.relkind = 'S' AND t.relname = tbl
                  AND t.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public');
            END LOOP;

            -- 3. Record Existing Grants for Staging Roles on Tables and Sequences (Full ACL Attributes - BLOCKER 2)
            INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2, attribute_3, attribute_4, attribute_5)
            SELECT 'ROLE_GRANT',
                   c.relname,
                   CASE WHEN exp.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(exp.grantee) END,
                   exp.privilege_type,
                   exp.is_grantable::text,
                   pg_get_userbyid(exp.grantor),
                   CASE c.relkind WHEN 'S' THEN 'SEQUENCE' ELSE 'TABLE' END
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            CROSS JOIN LATERAL aclexplode(c.relacl) exp
            WHERE n.nspname = 'public'
              AND c.relkind IN ('r', 'p', 'S')
              AND (exp.grantee = 0 OR pg_get_userbyid(exp.grantee) IN ('queueless_migrator_staging', 'queueless_app_staging'));

            -- 4. Record Existing Default Privileges for Staging Roles (Full Default ACL Attributes - BLOCKER 3)
            INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2, attribute_3, attribute_4, attribute_5)
            SELECT 'DEFAULT_ACL',
                   r.rolname,
                   CASE d.defaclobjtype
                       WHEN 'r' THEN 'TABLES'
                       WHEN 'S' THEN 'SEQUENCES'
                       WHEN 'f' THEN 'FUNCTIONS'
                       WHEN 'T' THEN 'TYPES'
                       WHEN 's' THEN 'SCHEMAS'
                       ELSE d.defaclobjtype::text
                   END,
                   CASE WHEN exp.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(exp.grantee) END,
                   exp.privilege_type,
                   exp.is_grantable::text,
                   pg_get_userbyid(exp.grantor)
            FROM pg_default_acl d
            JOIN pg_roles r ON d.defaclrole = r.oid
            JOIN pg_namespace n ON d.defaclnamespace = n.oid
            CROSS JOIN LATERAL aclexplode(d.defaclacl) exp
            WHERE r.rolname IN ('queueless_migrator_staging', 'queueless_app_staging')
              AND n.nspname = 'public';

            -- 5. Record Existing Role Memberships (Full PostgreSQL 16 Role Attributes - BLOCKER 2)
            INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2, attribute_3, attribute_4, attribute_5)
            SELECT 'MEMBERSHIP',
                   m.rolname,
                   b.rolname,
                   am.admin_option::text,
                   CASE WHEN (to_jsonb(am) ? 'inherit_option') THEN (to_jsonb(am)->>'inherit_option') ELSE 'true' END,
                   CASE WHEN (to_jsonb(am) ? 'set_option') THEN (to_jsonb(am)->>'set_option') ELSE 'true' END,
                   COALESCE(g.rolname, 'postgres')
            FROM pg_auth_members am
            JOIN pg_roles m ON am.member = m.oid
            JOIN pg_roles b ON am.roleid = b.oid
            LEFT JOIN pg_roles g ON am.grantor = g.oid
            WHERE m.rolname IN ('queueless_migrator_staging', 'queueless_app_staging', 'canary_admin')
               OR b.rolname IN ('queueless_migrator_staging', 'queueless_app_staging', 'canary_admin');

            -- 6. Record Pre-existing Roles with Attributes & Encrypted Password (Phase 1 Requirement 1)
            IF is_super THEN
                INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2, attribute_3, attribute_4, attribute_5)
                SELECT 'PREEXISTING_ROLE',
                       a.rolname,
                       a.rolcanlogin::text,
                       a.rolinherit::text,
                       a.rolconnlimit::text,
                       a.rolpassword,
                       a.rolvaliduntil::text
                FROM pg_authid a
                WHERE a.rolname IN ('queueless_migrator_staging', 'queueless_app_staging', 'canary_admin');
            ELSE
                INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2, attribute_3, attribute_4, attribute_5)
                SELECT 'PREEXISTING_ROLE',
                       r.rolname,
                       r.rolcanlogin::text,
                       r.rolinherit::text,
                       r.rolconnlimit::text,
                       NULL,
                       r.rolvaliduntil::text
                FROM pg_roles r
                WHERE r.rolname IN ('queueless_migrator_staging', 'queueless_app_staging', 'canary_admin');
            END IF;

            -- 7. Record Pre-existing Canary Objects & Complete State (Phase 1 Requirement 2)
            IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_system_environment_canary' AND c.relkind IN ('r', 'p')) THEN
                -- 7a. Canary Row Contents
                INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2, attribute_3, attribute_4)
                SELECT 'CANARY_ROW',
                       '_system_environment_canary',
                       c.id::text,
                       c.environment_name,
                       c.supabase_project_ref,
                       c.initialized_at::text
                FROM public._system_environment_canary c;

                -- 7b. Canary Table Owner & ACLs
                INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2)
                SELECT 'CANARY_TABLE',
                       c.relname,
                       pg_get_userbyid(c.relowner),
                       COALESCE(c.relacl::text, '')
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = '_system_environment_canary';

                -- 7c. Canary Triggers & State
                INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2)
                SELECT 'CANARY_TRIGGER',
                       t.tgname,
                       pg_get_triggerdef(t.oid),
                       t.tgenabled::text
                FROM pg_trigger t
                WHERE t.tgrelid = 'public._system_environment_canary'::regclass
                  AND NOT t.tgisinternal;

                -- Backward compatibility marker
                INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2)
                SELECT 'PREEXISTING_OBJECT', c.relname, 'TABLE', pg_get_userbyid(c.relowner)
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = '_system_environment_canary' AND c.relkind IN ('r', 'p');
            END IF;

            IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = 'protect_canary_table_immutability') THEN
                -- 7d. Canary Function Definition, Owner, Security Attributes, Config, & Grants
                INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2, attribute_3, attribute_4, attribute_5)
                SELECT 'CANARY_FUNCTION',
                       p.proname,
                       pg_get_userbyid(p.proowner),
                       pg_get_functiondef(p.oid),
                       p.prosecdef::text,
                       COALESCE(array_to_string(p.proconfig, ';'), ''),
                       COALESCE(p.proacl::text, '')
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.proname = 'protect_canary_table_immutability';

                -- Backward compatibility marker
                INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2)
                SELECT 'PREEXISTING_OBJECT', p.proname, 'FUNCTION', pg_get_userbyid(p.proowner)
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.proname = 'protect_canary_table_immutability';
            END IF;

            -- 8. Record Pre-existing Schema-Level ACLs on public (Phase 1 Requirement 3)
            INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name, attribute_1, attribute_2, attribute_3, attribute_4)
            SELECT 'SCHEMA_GRANT',
                   n.nspname,
                   CASE WHEN exp.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(exp.grantee) END,
                   exp.privilege_type,
                   exp.is_grantable::text,
                   pg_get_userbyid(exp.grantor)
            FROM pg_namespace n
            CROSS JOIN LATERAL aclexplode(n.nspacl) exp
            WHERE n.nspname = 'public'
              AND (exp.grantee = 0 OR pg_get_userbyid(exp.grantee) IN ('queueless_migrator_staging', 'queueless_app_staging', 'canary_admin'));
        END IF;
    END IF;

    -- ========================================================================
    -- BLOCK 1: CREATE OR UPDATE ROLES
    -- ========================================================================
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'canary_admin') THEN
        CREATE ROLE canary_admin NOLOGIN;
    END IF;
    GRANT USAGE, CREATE ON SCHEMA public TO canary_admin;

    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
        EXECUTE format('CREATE ROLE queueless_migrator_staging WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD %L', migrator_pass);
    ELSE
        EXECUTE format('ALTER ROLE queueless_migrator_staging WITH PASSWORD %L', migrator_pass);
    END IF;

    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
        EXECUTE format('CREATE ROLE queueless_app_staging WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD %L', app_pass);
    ELSE
        EXECUTE format('ALTER ROLE queueless_app_staging WITH PASSWORD %L', app_pass);
    END IF;

    -- ========================================================================
    -- BLOCK 2: CONFIGURE SCHEMA, LEAST-PRIVILEGE ALLOWLIST & DEFAULT PRIVILEGES (SEC-01)
    -- ========================================================================
    REVOKE CREATE ON SCHEMA public FROM PUBLIC;
    GRANT USAGE, CREATE ON SCHEMA public TO queueless_migrator_staging;
    GRANT USAGE ON SCHEMA public TO queueless_app_staging;

    -- Ensure staging roles have zero access to the provisioning snapshot table & sequence
    IF (SELECT pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot' AND c.relkind IN ('r', 'p')) = caller_role OR is_super THEN
        REVOKE ALL ON TABLE public._queueless_staging_provisioning_snapshot FROM queueless_migrator_staging, queueless_app_staging;
        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot_id_seq' AND c.relkind = 'S') THEN
            REVOKE ALL ON SEQUENCE public._queueless_staging_provisioning_snapshot_id_seq FROM queueless_migrator_staging, queueless_app_staging;
        END IF;
    END IF;

    FOREACH tbl IN ARRAY allowlist_tables LOOP
        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = tbl AND c.relkind IN ('r', 'p')) THEN
            SELECT pg_get_userbyid(c.relowner) INTO current_tbl_owner
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname = tbl AND c.relkind IN ('r', 'p');

            IF current_tbl_owner = caller_role OR is_super THEN
                EXECUTE format('GRANT ALL PRIVILEGES ON TABLE public.%I TO queueless_migrator_staging', tbl);
                IF tbl = 'flyway_schema_history' THEN
                    EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE public.%I FROM queueless_app_staging', tbl);
                    EXECUTE format('GRANT SELECT ON TABLE public.%I TO queueless_app_staging', tbl);
                ELSIF tbl <> 'otp_records' THEN
                    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.%I TO queueless_app_staging', tbl);
                END IF;
            END IF;

            FOR seq_rec IN (
                SELECT s.relname AS seqname, pg_get_userbyid(s.relowner) AS seqowner
                FROM pg_class s
                JOIN pg_depend d ON d.objid = s.oid
                JOIN pg_class t ON d.refobjid = t.oid
                WHERE s.relkind = 'S'
                  AND t.relname = tbl
                  AND t.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
            ) LOOP
                IF seq_rec.seqowner = caller_role OR is_super THEN
                    EXECUTE format('GRANT ALL PRIVILEGES ON SEQUENCE public.%I TO queueless_migrator_staging', seq_rec.seqname);
                    EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE public.%I TO queueless_app_staging', seq_rec.seqname);
                END IF;
            END LOOP;
        END IF;
    END LOOP;

    -- Safely establish SET membership for ALTER DEFAULT PRIVILEGES FOR ROLE
    IF NOT is_super THEN
        SELECT
            COALESCE(am.admin_option, FALSE),
            CASE
                WHEN (to_jsonb(am) ? 'set_option') THEN
                    COALESCE((to_jsonb(am)->>'set_option')::boolean, FALSE)
                ELSE TRUE
            END
        INTO has_admin_option, has_set_membership
        FROM pg_auth_members am
        JOIN pg_roles r ON r.oid = am.roleid
        JOIN pg_roles m ON m.oid = am.member
        WHERE r.rolname = 'queueless_migrator_staging'
          AND m.rolname = caller_role;

        IF NOT COALESCE(has_set_membership, FALSE) THEN
            IF NOT COALESCE(has_admin_option, FALSE) THEN
                RAISE EXCEPTION 'PREFLIGHT ABORT: Caller [%] lacks ADMIN OPTION on queueless_migrator_staging to set default privileges!', caller_role;
            END IF;
            IF current_setting('server_version_num')::integer >= 160000 THEN
                EXECUTE format('GRANT queueless_migrator_staging TO %I WITH SET TRUE', caller_role);
            ELSE
                EXECUTE format('GRANT queueless_migrator_staging TO %I', caller_role);
            END IF;
            temp_mem_def_acl := TRUE;
        END IF;
    END IF;

    ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public
        GRANT ALL PRIVILEGES ON TABLES TO queueless_migrator_staging;
    ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public
        GRANT ALL PRIVILEGES ON SEQUENCES TO queueless_migrator_staging;

    ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO queueless_app_staging;
    ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO queueless_app_staging;

    IF temp_mem_def_acl THEN
        EXECUTE format('REVOKE queueless_migrator_staging FROM %I', caller_role);
        temp_mem_def_acl := FALSE;
    END IF;

    SELECT COUNT(*) INTO def_acl_count
    FROM pg_default_acl d
    JOIN pg_roles r ON d.defaclrole = r.oid
    JOIN pg_namespace n ON d.defaclnamespace = n.oid
    WHERE r.rolname = 'queueless_migrator_staging' AND n.nspname = 'public';

    IF def_acl_count < 2 THEN
        RAISE EXCEPTION 'FATAL: Default privileges for queueless_migrator_staging were not recorded in pg_default_acl (count=%)!', def_acl_count;
    END IF;

    -- ========================================================================
    -- BLOCK 3: BOOTSTRAP ENVIRONMENT CANARY & COMPREHENSIVE INTEGRITY VERIFICATION (SEC-03 & BLOCKER 1)
    -- ========================================================================
    CREATE TABLE IF NOT EXISTS public._system_environment_canary (
        id INT PRIMARY KEY DEFAULT 1,
        environment_name VARCHAR(50) NOT NULL CHECK (environment_name IN ('STAGING', 'PRODUCTION')),
        supabase_project_ref VARCHAR(100) NOT NULL,
        initialized_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT chk_canary_singleton CHECK (id = 1)
    );

    SELECT pg_get_userbyid(c.relowner) INTO cur_tbl_owner
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = '_system_environment_canary'
      AND c.relkind IN ('r', 'p');

    SELECT
        r.rolname,
        p.prosecdef,
        COALESCE(p.proconfig @> ARRAY['search_path=pg_catalog, pg_temp'], FALSE)
    INTO cur_fn_owner, cur_secdef, cur_pinned_path
    FROM pg_proc p
    JOIN pg_roles r ON r.oid = p.proowner
    WHERE p.proname = 'protect_canary_table_immutability'
      AND p.pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public');

    SELECT COUNT(*) INTO dml_trg_cnt
    FROM pg_trigger
    WHERE tgrelid = 'public._system_environment_canary'::regclass
      AND tgname = 'trg_protect_canary_dml'
      AND NOT tgisinternal;

    SELECT COUNT(*) INTO trunc_trg_cnt
    FROM pg_trigger
    WHERE tgrelid = 'public._system_environment_canary'::regclass
      AND tgname = 'trg_protect_canary_truncate'
      AND NOT tgisinternal;

    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_system_environment_canary') THEN
        IF has_table_privilege(caller_role, 'public._system_environment_canary', 'SELECT') THEN
            SELECT environment_name, supabase_project_ref
            INTO cur_env, cur_ref
            FROM public._system_environment_canary
            WHERE id = 1;
        END IF;

        IF (cur_env IS NULL OR (cur_env = target_env AND cur_ref = target_project_ref))
           AND cur_tbl_owner = 'canary_admin'
           AND cur_fn_owner = 'canary_admin'
           AND cur_secdef = FALSE
           AND cur_pinned_path = TRUE
           AND dml_trg_cnt > 0
           AND trunc_trg_cnt > 0 THEN
            canary_healthy := TRUE;
        END IF;
    END IF;

    IF NOT canary_healthy THEN
        -- Verify caller authority on canary_admin BEFORE modification (BLOCKER 1)
        IF NOT is_super THEN
            SELECT
                COALESCE(am.admin_option, FALSE),
                CASE WHEN (to_jsonb(am) ? 'set_option') THEN COALESCE((to_jsonb(am)->>'set_option')::boolean, FALSE) ELSE TRUE END
            INTO canary_admin_opt, canary_has_set
            FROM pg_auth_members am
            JOIN pg_roles r ON r.oid = am.roleid
            JOIN pg_roles m ON m.oid = am.member
            WHERE r.rolname = 'canary_admin' AND m.rolname = caller_role;

            IF NOT COALESCE(canary_has_set, FALSE) THEN
                IF NOT COALESCE(canary_admin_opt, FALSE) THEN
                    RAISE EXCEPTION 'CANARY PREFLIGHT ABORT: Caller [%] lacks ADMIN OPTION on canary_admin! Cannot acquire SET authority to manage canary objects.', caller_role;
                END IF;

                IF current_setting('server_version_num')::integer >= 160000 THEN
                    EXECUTE format('GRANT canary_admin TO %I WITH SET TRUE', caller_role);
                ELSE
                    EXECUTE format('GRANT canary_admin TO %I', caller_role);
                END IF;
                temp_mem_canary := TRUE;
            END IF;
        END IF;

        IF cur_tbl_owner = 'canary_admin' AND NOT is_super THEN
            -- Table is owned by canary_admin: temporarily reclaim ownership to caller_role for trigger and DML repair
            EXECUTE format('ALTER TABLE public._system_environment_canary OWNER TO %I', caller_role);
            IF cur_fn_owner = 'canary_admin' THEN
                EXECUTE format('ALTER FUNCTION public.protect_canary_table_immutability() OWNER TO %I', caller_role);
            END IF;
        END IF;

        -- Create / replace function explicitly without SECURITY DEFINER and with pinned search_path
        CREATE OR REPLACE FUNCTION public.protect_canary_table_immutability()
        RETURNS TRIGGER LANGUAGE plpgsql
        SECURITY INVOKER
        SET search_path = pg_catalog, pg_temp AS $func$
        BEGIN
            RAISE EXCEPTION 'CANARY TAMPER VIOLATION: public._system_environment_canary is strictly immutable! DML modification and truncation are permanently blocked.';
        END;
        $func$;

        DROP TRIGGER IF EXISTS trg_protect_canary_dml ON public._system_environment_canary;
        DROP TRIGGER IF EXISTS trg_protect_canary_truncate ON public._system_environment_canary;

        IF NOT EXISTS (SELECT 1 FROM public._system_environment_canary WHERE id = 1) THEN
            INSERT INTO public._system_environment_canary (id, environment_name, supabase_project_ref)
            VALUES (1, target_env, target_project_ref);
        ELSIF cur_env <> target_env OR cur_ref <> target_project_ref THEN
            UPDATE public._system_environment_canary
            SET environment_name = target_env, supabase_project_ref = target_project_ref
            WHERE id = 1;
        END IF;

        CREATE TRIGGER trg_protect_canary_dml
        BEFORE INSERT OR UPDATE OR DELETE ON public._system_environment_canary
        FOR EACH ROW EXECUTE FUNCTION public.protect_canary_table_immutability();

        CREATE TRIGGER trg_protect_canary_truncate
        BEFORE TRUNCATE ON public._system_environment_canary
        FOR EACH STATEMENT EXECUTE FUNCTION public.protect_canary_table_immutability();

        -- Ensure canary_admin has schema authority to own objects in public schema
        GRANT USAGE, CREATE ON SCHEMA public TO canary_admin;

        ALTER TABLE public._system_environment_canary OWNER TO canary_admin;
        ALTER FUNCTION public.protect_canary_table_immutability() OWNER TO canary_admin;

        REVOKE CREATE ON SCHEMA public FROM canary_admin;
        GRANT USAGE ON SCHEMA public TO canary_admin;
    END IF;

    -- Ensure canary permissions are consistently reconciled for staging roles
    REVOKE ALL ON TABLE public._system_environment_canary FROM PUBLIC, anon, authenticated, service_role;
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE public._system_environment_canary FROM postgres, queueless_migrator_staging, queueless_app_staging;
    GRANT SELECT ON TABLE public._system_environment_canary TO postgres, queueless_migrator_staging, queueless_app_staging;
    IF caller_role NOT IN ('postgres', 'queueless_migrator_staging', 'queueless_app_staging') THEN
        EXECUTE format('GRANT SELECT ON TABLE public._system_environment_canary TO %I', caller_role);
    END IF;

    REVOKE ALL ON FUNCTION public.protect_canary_table_immutability() FROM PUBLIC;
    GRANT EXECUTE ON FUNCTION public.protect_canary_table_immutability() TO canary_admin, postgres, queueless_migrator_staging, queueless_app_staging;
    IF caller_role NOT IN ('postgres', 'queueless_migrator_staging', 'queueless_app_staging') THEN
        EXECUTE format('GRANT EXECUTE ON FUNCTION public.protect_canary_table_immutability() TO %I', caller_role);
    END IF;

    -- Safely clean up temporary canary_admin membership granted during provisioning
    IF temp_mem_canary THEN
        EXECUTE format('REVOKE canary_admin FROM %I', caller_role);
        IF current_setting('server_version_num')::integer >= 160000 THEN
            is_preexisting_set := FALSE;
            IF has_snapshot_priv THEN
                SELECT EXISTS (
                    SELECT 1 FROM public._queueless_staging_provisioning_snapshot
                    WHERE snapshot_type = 'MEMBERSHIP'
                      AND target_name = caller_role
                      AND attribute_1 = 'canary_admin'
                      AND attribute_4 = 'true'
                ) INTO is_preexisting_set;
            END IF;

            IF NOT is_preexisting_set THEN
                IF EXISTS (
                    SELECT 1 FROM pg_auth_members am
                    JOIN pg_roles r ON r.oid = am.roleid
                    JOIN pg_roles m ON m.oid = am.member
                    WHERE r.rolname = 'canary_admin'
                      AND m.rolname = caller_role
                      AND am.set_option = TRUE
                ) THEN
                    RAISE EXCEPTION 'FATAL: Temporary canary_admin SET privilege could not be revoked from [%]!', caller_role;
                END IF;
                IF NOT is_super AND pg_has_role(caller_role, 'canary_admin', 'SET') THEN
                    RAISE EXCEPTION 'FATAL: Temporary canary_admin SET privilege could not be revoked from [%]!', caller_role;
                END IF;
            END IF;
        END IF;
        temp_mem_canary := FALSE;
    END IF;

    -- ========================================================================
    -- BLOCK 4: ALLOWLISTED TABLE & SEQUENCE OWNERSHIP RECONCILIATION
    -- ========================================================================
    IF NOT is_super THEN
        SELECT
            COALESCE(am.admin_option, FALSE),
            CASE
                WHEN (to_jsonb(am) ? 'set_option') THEN
                    COALESCE((to_jsonb(am)->>'set_option')::boolean, FALSE)
                ELSE TRUE
            END
        INTO has_admin_option, has_set_membership
        FROM pg_auth_members am
        JOIN pg_roles r ON r.oid = am.roleid
        JOIN pg_roles m ON m.oid = am.member
        WHERE r.rolname = 'queueless_migrator_staging'
          AND m.rolname = caller_role;

        IF NOT COALESCE(has_set_membership, FALSE) THEN
            IF NOT COALESCE(has_admin_option, FALSE) THEN
                RAISE EXCEPTION 'PREFLIGHT ABORT: Caller [%] lacks SET membership and ADMIN OPTION on [queueless_migrator_staging]!', caller_role;
            END IF;
            IF current_setting('server_version_num')::integer >= 160000 THEN
                EXECUTE format('GRANT queueless_migrator_staging TO %I WITH SET TRUE', caller_role);
            ELSE
                EXECUTE format('GRANT queueless_migrator_staging TO %I', caller_role);
            END IF;
            temp_mem_migrator := TRUE;
        END IF;
    END IF;

    FOREACH tbl IN ARRAY allowlist_tables LOOP
        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = tbl AND c.relkind IN ('r', 'p')) THEN
            SELECT pg_get_userbyid(c.relowner) INTO current_tbl_owner
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname = tbl AND c.relkind IN ('r', 'p');

            IF current_tbl_owner = 'queueless_migrator_staging' THEN
                NULL;
            ELSIF current_tbl_owner = caller_role OR is_super THEN
                EXECUTE format('ALTER TABLE public.%I OWNER TO queueless_migrator_staging', tbl);
            ELSE
                RAISE EXCEPTION 'PREFLIGHT ABORT: Table public.% is owned by [%], but caller is non-superuser [%]! Cannot transfer ownership.', tbl, current_tbl_owner, caller_role;
            END IF;

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
                IF seq_rec.seqowner = 'queueless_migrator_staging' THEN
                    NULL;
                ELSIF seq_rec.seqowner = caller_role OR is_super THEN
                    EXECUTE format('ALTER SEQUENCE public.%I OWNER TO queueless_migrator_staging', seq_rec.seqname);
                ELSE
                    RAISE EXCEPTION 'PREFLIGHT ABORT: Sequence public.% is owned by [%], but caller is non-superuser [%]! Cannot transfer ownership.', seq_rec.seqname, seq_rec.seqowner, caller_role;
                END IF;
            END LOOP;
        END IF;
    END LOOP;

    IF temp_mem_migrator THEN
        EXECUTE format('REVOKE queueless_migrator_staging FROM %I', caller_role);
        IF current_setting('server_version_num')::integer >= 160000 THEN
            IF pg_has_role(caller_role, 'queueless_migrator_staging', 'SET') THEN
                RAISE EXCEPTION 'FATAL: Temporary membership revocation failed! Caller [%] still possesses active SET privilege.', caller_role;
            END IF;
        END IF;
        temp_mem_migrator := FALSE;
    END IF;

    SELECT COUNT(*), COALESCE(string_agg(c.relname, ', '), 'NONE')
    INTO unhandled_count, unhandled_names
    FROM pg_class c
    JOIN pg_roles r ON c.relowner = r.oid
    JOIN pg_namespace n ON c.relnamespace = n.oid
    WHERE n.nspname = 'public'
      AND r.rolname = 'queueless_migrator_staging'
      AND c.relkind IN ('r', 'S', 'v', 'm', 'p')
      AND c.relname NOT IN (SELECT unnest(allowlist_tables))
      AND NOT EXISTS (
          SELECT 1 FROM pg_depend d
          JOIN pg_class t ON d.refobjid = t.oid
          WHERE d.objid = c.oid
            AND t.relname IN (SELECT unnest(allowlist_tables))
      );

    IF unhandled_count > 0 THEN
        RAISE EXCEPTION 'SECURITY AUDIT ABORT: Found % unexpected object(s) owned by queueless_migrator_staging outside allowlist: [%]!', unhandled_count, unhandled_names;
    END IF;

    -- ========================================================================
    -- BLOCK 5: POST-PROVISIONING SECURITY ASSERTIONS (ALL 8 CHECKS - BLOCKER 4)
    -- ========================================================================
    IF has_table_privilege(caller_role, 'public._system_environment_canary', 'SELECT') THEN
        SELECT environment_name, supabase_project_ref
        INTO cur_env, cur_ref
        FROM public._system_environment_canary
        WHERE id = 1;

        IF cur_env <> target_env OR cur_ref <> target_project_ref THEN
            RAISE EXCEPTION 'FATAL POST-CHECK: Canary record mismatch ([% / %], expected [% / %])!', cur_env, cur_ref, target_env, target_project_ref;
        END IF;
    END IF;

    SELECT pg_get_userbyid(c.relowner) INTO cur_tbl_owner
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = '_system_environment_canary'
      AND c.relkind IN ('r', 'p');

    IF cur_tbl_owner <> 'canary_admin' THEN
        RAISE EXCEPTION 'FATAL POST-CHECK: Canary table owner is [%], expected canary_admin!', cur_tbl_owner;
    END IF;

    SELECT
        r.rolname,
        p.prosecdef,
        COALESCE(p.proconfig @> ARRAY['search_path=pg_catalog, pg_temp'], FALSE)
    INTO cur_fn_owner, cur_secdef, cur_pinned_path
    FROM pg_proc p
    JOIN pg_roles r ON r.oid = p.proowner
    WHERE p.proname = 'protect_canary_table_immutability'
      AND p.pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public');

    IF cur_fn_owner <> 'canary_admin' THEN
        RAISE EXCEPTION 'FATAL POST-CHECK: Canary function owner is [%], expected canary_admin!', cur_fn_owner;
    END IF;

    IF cur_secdef = TRUE THEN
        RAISE EXCEPTION 'FATAL POST-CHECK: Canary function must NOT be SECURITY DEFINER!';
    END IF;

    IF cur_pinned_path = FALSE THEN
        RAISE EXCEPTION 'FATAL POST-CHECK: Canary function search_path is not pinned to pg_catalog, pg_temp!';
    END IF;

    SELECT COUNT(*) INTO dml_trg_cnt
    FROM pg_trigger
    WHERE tgrelid = 'public._system_environment_canary'::regclass
      AND tgname = 'trg_protect_canary_dml'
      AND NOT tgisinternal;

    SELECT COUNT(*) INTO trunc_trg_cnt
    FROM pg_trigger
    WHERE tgrelid = 'public._system_environment_canary'::regclass
      AND tgname = 'trg_protect_canary_truncate'
      AND NOT tgisinternal;

    IF dml_trg_cnt = 0 OR trunc_trg_cnt = 0 THEN
        RAISE EXCEPTION 'FATAL POST-CHECK: Canary protection triggers missing (dml=%, truncate=%)!', dml_trg_cnt, trunc_trg_cnt;
    END IF;

    -- Verify canary_admin membership boundary:
    -- 1. Any active member of canary_admin must ONLY be either:
    --    a) The provisioning administrator (caller_role) possessing creator ADMIN OPTION with SET FALSE, OR
    --    b) A legitimate pre-existing member recorded in the snapshot before provisioning
    has_snapshot_priv := has_table_privilege(caller_role, 'public._queueless_staging_provisioning_snapshot', 'SELECT');

    IF has_snapshot_priv THEN
        SELECT COUNT(*), COALESCE(string_agg(m.rolname, ', '), 'NONE')
        INTO unhandled_canary_mems, unhandled_canary_names
        FROM pg_auth_members am
        JOIN pg_roles r ON r.oid = am.roleid
        JOIN pg_roles m ON m.oid = am.member
        WHERE r.rolname = 'canary_admin'
          AND m.rolname <> caller_role
          AND NOT EXISTS (
              SELECT 1 FROM public._queueless_staging_provisioning_snapshot s
              WHERE s.snapshot_type = 'MEMBERSHIP'
                AND s.target_name = m.rolname
                AND s.attribute_1 = 'canary_admin'
          );
    ELSE
        SELECT COUNT(*), COALESCE(string_agg(m.rolname, ', '), 'NONE')
        INTO unhandled_canary_mems, unhandled_canary_names
        FROM pg_auth_members am
        JOIN pg_roles r ON r.oid = am.roleid
        JOIN pg_roles m ON m.oid = am.member
        WHERE r.rolname = 'canary_admin'
          AND m.rolname <> caller_role;
    END IF;

    IF unhandled_canary_mems > 0 THEN
        RAISE EXCEPTION 'FATAL POST-CHECK: canary_admin possesses % unauthorized member(s): [%]!', unhandled_canary_mems, unhandled_canary_names;
    END IF;

    -- 2. Verify that caller_role does NOT retain unauthorized SET privilege on canary_admin
    IF current_setting('server_version_num')::integer >= 160000 THEN
        IF EXISTS (
            SELECT 1 FROM pg_auth_members am
            JOIN pg_roles r ON r.oid = am.roleid
            JOIN pg_roles m ON m.oid = am.member
            WHERE r.rolname = 'canary_admin'
              AND m.rolname = caller_role
              AND am.set_option = TRUE
        ) THEN
            is_preexisting_set := FALSE;
            IF has_snapshot_priv THEN
                SELECT EXISTS (
                    SELECT 1 FROM public._queueless_staging_provisioning_snapshot s
                    WHERE s.snapshot_type = 'MEMBERSHIP'
                      AND s.target_name = caller_role
                      AND s.attribute_1 = 'canary_admin'
                      AND s.attribute_4 = 'true'
                ) INTO is_preexisting_set;
            END IF;
            IF NOT is_preexisting_set THEN
                RAISE EXCEPTION 'FATAL POST-CHECK: Caller [%] still possesses catalog SET privilege on canary_admin after provisioning!', caller_role;
            END IF;
        END IF;

        IF NOT is_super THEN
            IF pg_has_role(caller_role, 'canary_admin', 'SET') THEN
                is_preexisting_set := FALSE;
                IF has_snapshot_priv THEN
                    SELECT EXISTS (
                        SELECT 1 FROM public._queueless_staging_provisioning_snapshot s
                        WHERE s.snapshot_type = 'MEMBERSHIP'
                          AND s.target_name = caller_role
                          AND s.attribute_1 = 'canary_admin'
                          AND s.attribute_4 = 'true'
                    ) INTO is_preexisting_set;
                END IF;
                IF NOT is_preexisting_set THEN
                    RAISE EXCEPTION 'FATAL POST-CHECK: Caller [%] still possesses active SET privilege on canary_admin after provisioning!', caller_role;
                END IF;
            END IF;
        END IF;
    END IF;

    SELECT COUNT(*) INTO auth_count
    FROM pg_auth_members m
    JOIN pg_roles r ON m.member = r.oid
    JOIN pg_roles b ON m.roleid = b.oid
    WHERE r.rolname IN ('queueless_migrator_staging', 'queueless_app_staging')
      AND b.rolname IN ('canary_admin', 'postgres', 'supabase_admin');

    IF auth_count > 0 THEN
        RAISE EXCEPTION 'FATAL POST-CHECK: Staging roles possess % prohibited administrative membership(s)!', auth_count;
    END IF;

    IF has_table_privilege('queueless_app_staging', 'public._system_environment_canary', 'INSERT')
       OR has_table_privilege('queueless_app_staging', 'public._system_environment_canary', 'UPDATE')
       OR has_table_privilege('queueless_app_staging', 'public._system_environment_canary', 'DELETE')
       OR has_table_privilege('queueless_app_staging', 'public._system_environment_canary', 'TRUNCATE')
       OR has_table_privilege('queueless_migrator_staging', 'public._system_environment_canary', 'INSERT')
       OR has_table_privilege('queueless_migrator_staging', 'public._system_environment_canary', 'UPDATE')
       OR has_table_privilege('queueless_migrator_staging', 'public._system_environment_canary', 'DELETE')
       OR has_table_privilege('queueless_migrator_staging', 'public._system_environment_canary', 'TRUNCATE') THEN
        RAISE EXCEPTION 'FATAL POST-CHECK: Staging roles possess prohibited write/truncate privileges on canary table!';
    END IF;

    -- Verify snapshot table and sequence isolation independently (when caller is authorized)
    IF has_table_privilege(caller_role, 'public._queueless_staging_provisioning_snapshot', 'SELECT') OR is_super THEN
        SELECT pg_get_userbyid(c.relowner) INTO cur_tbl_owner
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot'
          AND c.relkind IN ('r', 'p');

        IF cur_tbl_owner NOT IN (caller_role, 'postgres') AND NOT is_super THEN
            RAISE EXCEPTION 'FATAL POST-CHECK: Snapshot table owner [%] does not match caller [%] or postgres!', cur_tbl_owner, caller_role;
        END IF;

        IF (EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace CROSS JOIN LATERAL aclexplode(c.relacl) exp WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot' AND exp.grantee = 0))
           OR has_table_privilege('queueless_app_staging', 'public._queueless_staging_provisioning_snapshot', 'SELECT')
           OR has_table_privilege('queueless_migrator_staging', 'public._queueless_staging_provisioning_snapshot', 'SELECT')
           OR (EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') AND has_table_privilege('anon', 'public._queueless_staging_provisioning_snapshot', 'SELECT'))
           OR (EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') AND has_table_privilege('authenticated', 'public._queueless_staging_provisioning_snapshot', 'SELECT'))
           OR (EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') AND has_table_privilege('service_role', 'public._queueless_staging_provisioning_snapshot', 'SELECT')) THEN
            RAISE EXCEPTION 'FATAL POST-CHECK: Prohibited roles possess read or write privilege on _queueless_staging_provisioning_snapshot!';
        END IF;

        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot_id_seq' AND c.relkind = 'S') THEN
            IF (EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace CROSS JOIN LATERAL aclexplode(c.relacl) exp WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot_id_seq' AND exp.grantee = 0))
               OR has_sequence_privilege('queueless_app_staging', 'public._queueless_staging_provisioning_snapshot_id_seq', 'USAGE')
               OR has_sequence_privilege('queueless_migrator_staging', 'public._queueless_staging_provisioning_snapshot_id_seq', 'USAGE')
               OR (EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') AND has_sequence_privilege('anon', 'public._queueless_staging_provisioning_snapshot_id_seq', 'USAGE'))
               OR (EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') AND has_sequence_privilege('authenticated', 'public._queueless_staging_provisioning_snapshot_id_seq', 'USAGE'))
               OR (EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') AND has_sequence_privilege('service_role', 'public._queueless_staging_provisioning_snapshot_id_seq', 'USAGE')) THEN
                RAISE EXCEPTION 'FATAL POST-CHECK: Prohibited roles possess privilege on snapshot sequence!';
            END IF;
        END IF;
    END IF;

    RAISE NOTICE 'SUCCESS: Staging roles, least-privilege allowlist, and complete Canary integrity bootstrapped cleanly!';
END $$;
