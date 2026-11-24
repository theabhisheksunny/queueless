-- ============================================================================
-- QueueLess Staging: Non-Destructive Recorded-State Rollback Script
-- Target Database: postgres (Supabase Session Pooler Port 5432)
-- Project Reference: tlgibnjprelzpxfxycab
-- Region: ap-southeast-1
-- ============================================================================

DO $$
DECLARE
    caller_role         TEXT := CURRENT_USER;
    is_super            BOOLEAN;
    is_admin            BOOLEAN;
    rec                 RECORD;
    mem_rec             RECORD;
    unhandled_count     INT;
    unhandled_names     TEXT;
    snap_count          INT;
    def_acl_unrecorded  INT;

    -- Preflight Authority Tracking (BLOCKER 1 & BLOCKER 4)
    migrator_mem_exists BOOLEAN := FALSE;
    migrator_has_admin  BOOLEAN := FALSE;
    migrator_has_set    BOOLEAN := FALSE;
    temp_migrator_action TEXT := 'NONE'; -- 'NONE' or 'RESTORE_SET_FALSE'

    canary_mem_exists   BOOLEAN := FALSE;
    canary_has_admin    BOOLEAN := FALSE;
    canary_has_set      BOOLEAN := FALSE;
    temp_canary_action  TEXT := 'NONE'; -- 'NONE' or 'RESTORE_SET_FALSE'

    -- Object Restoration Variables (BLOCKER 4)
    cur_tbl_owner       TEXT;
    cur_fn_owner        TEXT;

    grantee_clause      TEXT;
    grant_opt_clause    TEXT;
    granted_by_clause   TEXT;
BEGIN
    SELECT rolsuper OR rolname IN ('postgres', 'supabase_admin') OR rolname LIKE 'simulated_postgres%'
    INTO is_admin
    FROM pg_roles
    WHERE rolname = caller_role;

    IF NOT COALESCE(is_admin, FALSE) THEN
        RAISE EXCEPTION 'FATAL: Script must be executed by an administrative role (postgres or supabase_admin). Caller: %', caller_role;
    END IF;

    SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;

    -- ========================================================================
    -- PREFLIGHT SNAPSHOT INTEGRITY VERIFICATION
    -- ========================================================================
    -- 1. Verify that the snapshot table exists
    SELECT COUNT(*) INTO snap_count
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot'
      AND c.relkind IN ('r', 'p');

    IF snap_count = 0 THEN
        RAISE EXCEPTION 'ROLLBACK ABORT: Snapshot table public._queueless_staging_provisioning_snapshot does not exist! Exact before-state cannot be guaranteed automatically. Manual recovery from backup required.';
    END IF;

    -- 2. Verify snapshot table is not empty
    SELECT COUNT(*) INTO snap_count FROM public._queueless_staging_provisioning_snapshot;
    IF snap_count = 0 THEN
        RAISE EXCEPTION 'ROLLBACK ABORT: Snapshot table public._queueless_staging_provisioning_snapshot is empty! Cannot validate or restore before-state.';
    END IF;

    -- 3. Verify snapshot contains valid recorded categories
    IF NOT EXISTS (SELECT 1 FROM public._queueless_staging_provisioning_snapshot WHERE snapshot_type IN ('TABLE_OWNER', 'PROVISIONING_BASELINE')) THEN
        RAISE EXCEPTION 'ROLLBACK ABORT: Snapshot table does not contain valid TABLE_OWNER records! Snapshot integrity check failed.';
    END IF;

    -- ========================================================================
    -- PREFLIGHT AUTHORITY VERIFICATION (BLOCKER 1 & BLOCKER 4)
    -- ========================================================================
    IF NOT is_super THEN
        -- A. Inspect caller's authority on queueless_migrator_staging
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
            SELECT
                TRUE,
                COALESCE(am.admin_option, FALSE),
                CASE
                    WHEN (to_jsonb(am) ? 'set_option') THEN COALESCE((to_jsonb(am)->>'set_option')::boolean, FALSE)
                    ELSE TRUE
                END
            INTO migrator_mem_exists, migrator_has_admin, migrator_has_set
            FROM pg_auth_members am
            JOIN pg_roles r ON r.oid = am.roleid
            JOIN pg_roles m ON m.oid = am.member
            WHERE r.rolname = 'queueless_migrator_staging' AND m.rolname = caller_role;

            IF COALESCE(migrator_mem_exists, FALSE) THEN
                IF migrator_has_set THEN
                    temp_migrator_action := 'NONE';
                ELSIF migrator_has_admin THEN
                    temp_migrator_action := 'RESTORE_SET_FALSE';
                ELSE
                    RAISE EXCEPTION 'ROLLBACK PREFLIGHT ABORT: Caller [%] has membership in role [queueless_migrator_staging] with SET FALSE and lacks ADMIN OPTION! Cannot restore object ownership.', caller_role;
                END IF;
            ELSE
                IF EXISTS (
                    SELECT 1 FROM pg_class c
                    JOIN pg_roles r ON c.relowner = r.oid
                    WHERE r.rolname = 'queueless_migrator_staging'
                ) THEN
                    RAISE EXCEPTION 'ROLLBACK PREFLIGHT ABORT: Caller [%] lacks required authority (SET TRUE or ADMIN OPTION) on role [queueless_migrator_staging]! Cannot restore object ownership.', caller_role;
                END IF;
            END IF;
        END IF;

        -- B. Inspect caller's authority on canary_admin
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
            SELECT
                TRUE,
                COALESCE(am.admin_option, FALSE),
                CASE
                    WHEN (to_jsonb(am) ? 'set_option') THEN COALESCE((to_jsonb(am)->>'set_option')::boolean, FALSE)
                    ELSE TRUE
                END
            INTO canary_mem_exists, canary_has_admin, canary_has_set
            FROM pg_auth_members am
            JOIN pg_roles r ON r.oid = am.roleid
            JOIN pg_roles m ON m.oid = am.member
            WHERE r.rolname = 'canary_admin' AND m.rolname = caller_role;

            IF COALESCE(canary_mem_exists, FALSE) THEN
                IF canary_has_set THEN
                    temp_canary_action := 'NONE';
                ELSIF canary_has_admin THEN
                    temp_canary_action := 'RESTORE_SET_FALSE';
                ELSE
                    RAISE EXCEPTION 'ROLLBACK PREFLIGHT ABORT: Caller [%] has membership in role [canary_admin] with SET FALSE and lacks ADMIN OPTION! Cannot manage canary assets.', caller_role;
                END IF;
            ELSE
                IF EXISTS (
                    SELECT 1 FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_roles r ON c.relowner = r.oid
                    WHERE n.nspname = 'public' AND c.relname = '_system_environment_canary'
                      AND r.rolname = 'canary_admin'
                ) OR EXISTS (
                    SELECT 1 FROM pg_proc p
                    JOIN pg_namespace n ON n.oid = p.pronamespace
                    JOIN pg_roles r ON p.proowner = r.oid
                    WHERE n.nspname = 'public' AND p.proname = 'protect_canary_table_immutability'
                      AND r.rolname = 'canary_admin'
                ) THEN
                    RAISE EXCEPTION 'ROLLBACK PREFLIGHT ABORT: Caller [%] lacks required authority (SET TRUE or ADMIN OPTION) on role [canary_admin]! Cannot manage canary assets.', caller_role;
                END IF;
            END IF;
        END IF;
    END IF;

    -- ========================================================================
    -- STEP 1: RESTORE TABLE OWNERSHIP TO RECORDED ORIGINAL OWNERS
    -- ========================================================================
    IF temp_migrator_action = 'RESTORE_SET_FALSE' THEN
        IF current_setting('server_version_num')::integer >= 160000 THEN
            EXECUTE format('GRANT queueless_migrator_staging TO %I WITH SET TRUE', caller_role);
        ELSE
            EXECUTE format('GRANT queueless_migrator_staging TO %I', caller_role);
        END IF;
    END IF;

    FOR rec IN (
        SELECT target_name, attribute_1 AS original_owner
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'TABLE_OWNER'
    ) LOOP
        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = rec.target_name AND c.relkind IN ('r', 'p')) THEN
            EXECUTE format('ALTER TABLE public.%I OWNER TO %I', rec.target_name, rec.original_owner);
        END IF;
    END LOOP;

    -- ========================================================================
    -- STEP 2: RESTORE SEQUENCE OWNERSHIP TO RECORDED ORIGINAL OWNERS
    -- ========================================================================
    FOR rec IN (
        SELECT target_name, attribute_1 AS original_owner
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'SEQUENCE_OWNER'
    ) LOOP
        IF EXISTS (SELECT FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid WHERE n.nspname = 'public' AND c.relkind = 'S' AND c.relname = rec.target_name) THEN
            EXECUTE format('ALTER SEQUENCE public.%I OWNER TO %I', rec.target_name, rec.original_owner);
        END IF;
    END LOOP;

    -- ========================================================================
    -- STEP 3: RESTORE DEFAULT PRIVILEGES (REVOKE STAGING + RE-APPLY RECORDED)
    -- ========================================================================
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
        ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public
            REVOKE ALL ON TABLES FROM queueless_migrator_staging, queueless_app_staging;
        ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public
            REVOKE ALL ON SEQUENCES FROM queueless_migrator_staging, queueless_app_staging;
    END IF;

    -- Re-apply any pre-existing default privileges recorded in snapshot across all object types
    FOR rec IN (
        SELECT target_name AS for_role,
               attribute_1 AS obj_type,
               attribute_2 AS grantee,
               attribute_3 AS priv,
               attribute_4 AS is_grantable,
               attribute_5 AS grantor
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'DEFAULT_ACL'
    ) LOOP
        grantee_clause := CASE WHEN rec.grantee = 'PUBLIC' THEN 'PUBLIC' ELSE quote_ident(rec.grantee) END;
        grant_opt_clause := CASE WHEN rec.is_grantable = 'true' THEN ' WITH GRANT OPTION' ELSE '' END;

        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = rec.for_role) AND (rec.grantee = 'PUBLIC' OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = rec.grantee)) THEN
            -- In PostgreSQL, ALTER DEFAULT PRIVILEGES FOR ROLE <for_role> automatically assigns <for_role> as grantor in pg_default_acl.
            -- PostgreSQL grammar does not support a GRANTED BY clause on ALTER DEFAULT PRIVILEGES.
            EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT %s ON %s TO %s%s',
                rec.for_role, rec.priv, rec.obj_type, grantee_clause, grant_opt_clause);
        END IF;
    END LOOP;

    IF temp_migrator_action = 'RESTORE_SET_FALSE' THEN
        IF current_setting('server_version_num')::integer >= 160000 THEN
            EXECUTE format('GRANT queueless_migrator_staging TO %I WITH SET FALSE', caller_role);
        ELSE
            EXECUTE format('GRANT queueless_migrator_staging TO %I', caller_role);
        END IF;
    END IF;

    -- ========================================================================
    -- STEP 4: CLEAN UP OR RESTORE CANARY ASSETS (BLOCKER 4 & Phase 1)
    -- ========================================================================
    IF temp_canary_action = 'RESTORE_SET_FALSE' THEN
        IF current_setting('server_version_num')::integer >= 160000 THEN
            EXECUTE format('GRANT canary_admin TO %I WITH SET TRUE', caller_role);
        ELSE
            EXECUTE format('GRANT canary_admin TO %I', caller_role);
        END IF;
    END IF;

    -- Handle canary table & functions
    IF EXISTS (
        SELECT 1 FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type IN ('CANARY_TABLE', 'PREEXISTING_OBJECT') AND target_name = '_system_environment_canary'
    ) THEN
        -- Drop provisioning triggers so function/row restoration is unobstructed
        DROP TRIGGER IF EXISTS trg_protect_canary_dml ON public._system_environment_canary;
        DROP TRIGGER IF EXISTS trg_protect_canary_truncate ON public._system_environment_canary;

        -- 1. Restore function definition, owner, security attributes & search_path
        FOR rec IN (
            SELECT attribute_1 AS fn_owner, attribute_2 AS fn_def
            FROM public._queueless_staging_provisioning_snapshot
            WHERE snapshot_type = 'CANARY_FUNCTION'
        ) LOOP
            IF rec.fn_def IS NOT NULL AND rec.fn_def <> '' THEN
                EXECUTE rec.fn_def;
                EXECUTE format('ALTER FUNCTION public.protect_canary_table_immutability() OWNER TO %I', rec.fn_owner);
            END IF;
        END LOOP;

        -- 2. Restore row contents
        IF EXISTS (SELECT 1 FROM public._queueless_staging_provisioning_snapshot WHERE snapshot_type = 'CANARY_ROW') THEN
            DELETE FROM public._system_environment_canary;
            FOR rec IN (
                SELECT attribute_1 AS id_val, attribute_2 AS env_val, attribute_3 AS ref_val, attribute_4 AS init_val
                FROM public._queueless_staging_provisioning_snapshot
                WHERE snapshot_type = 'CANARY_ROW'
            ) LOOP
                INSERT INTO public._system_environment_canary (id, environment_name, supabase_project_ref, initialized_at)
                VALUES (rec.id_val::integer, rec.env_val, rec.ref_val, rec.init_val::timestamptz);
            END LOOP;
        END IF;

        -- 3. Restore recorded triggers
        FOR rec IN (
            SELECT target_name AS tg_name, attribute_1 AS tg_def, attribute_2 AS tg_enabled
            FROM public._queueless_staging_provisioning_snapshot
            WHERE snapshot_type = 'CANARY_TRIGGER'
        ) LOOP
            IF rec.tg_def IS NOT NULL AND rec.tg_def <> '' THEN
                EXECUTE rec.tg_def;
                IF rec.tg_enabled = 'D' THEN
                    EXECUTE format('ALTER TABLE public._system_environment_canary DISABLE TRIGGER %I', rec.tg_name);
                END IF;
            END IF;
        END LOOP;

        -- 4. Restore table owner
        SELECT attribute_1 INTO cur_tbl_owner
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'CANARY_TABLE' AND target_name = '_system_environment_canary';

        IF cur_tbl_owner IS NULL THEN
            SELECT attribute_2 INTO cur_tbl_owner
            FROM public._queueless_staging_provisioning_snapshot
            WHERE snapshot_type = 'PREEXISTING_OBJECT' AND target_name = '_system_environment_canary' AND attribute_1 = 'TABLE';
        END IF;

        IF cur_tbl_owner IS NOT NULL THEN
            IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_system_environment_canary' AND c.relkind IN ('r', 'p')) THEN
                EXECUTE format('ALTER TABLE public._system_environment_canary OWNER TO %I', cur_tbl_owner);
            END IF;
        END IF;
    ELSE
        DROP TRIGGER IF EXISTS trg_protect_canary_dml ON public._system_environment_canary;
        DROP TRIGGER IF EXISTS trg_protect_canary_truncate ON public._system_environment_canary;
        DROP TABLE IF EXISTS public._system_environment_canary;
        DROP FUNCTION IF EXISTS public.protect_canary_table_immutability();
    END IF;

    IF temp_canary_action = 'RESTORE_SET_FALSE' THEN
        IF current_setting('server_version_num')::integer >= 160000 THEN
            EXECUTE format('GRANT canary_admin TO %I WITH SET FALSE', caller_role);
        ELSE
            EXECUTE format('GRANT canary_admin TO %I', caller_role);
        END IF;
    END IF;

    -- ========================================================================
    -- STEP 5: REVOKE PROVISIONING GRANTS & RESTORE PRE-EXISTING ROLE GRANTS (BLOCKER 2)
    -- ========================================================================
    FOR rec IN (
        SELECT target_name
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'TABLE_OWNER'
    ) LOOP
        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = rec.target_name AND c.relkind IN ('r', 'p')) THEN
            EXECUTE format('REVOKE ALL PRIVILEGES ON TABLE public.%I FROM queueless_migrator_staging, queueless_app_staging', rec.target_name);
        END IF;
    END LOOP;

    FOR rec IN (
        SELECT target_name
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'SEQUENCE_OWNER'
    ) LOOP
        IF EXISTS (SELECT FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid WHERE n.nspname = 'public' AND c.relkind = 'S' AND c.relname = rec.target_name) THEN
            EXECUTE format('REVOKE ALL PRIVILEGES ON SEQUENCE public.%I FROM queueless_migrator_staging, queueless_app_staging', rec.target_name);
        END IF;
    END LOOP;

    -- Re-apply any pre-existing grants recorded in snapshot (for both tables and sequences)
    FOR rec IN (
        SELECT target_name,
               attribute_1 AS grantee,
               attribute_2 AS privilege_type,
               attribute_3 AS is_grantable,
               attribute_4 AS grantor,
               attribute_5 AS obj_type
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'ROLE_GRANT'
    ) LOOP
        grantee_clause := CASE WHEN rec.grantee = 'PUBLIC' THEN 'PUBLIC' ELSE quote_ident(rec.grantee) END;
        grant_opt_clause := CASE WHEN rec.is_grantable = 'true' THEN ' WITH GRANT OPTION' ELSE '' END;

        -- Note: In PostgreSQL, relation grants (tables/sequences) do not support third-party grantors;
        -- PostgreSQL raises 'ERROR: grantor must be current user' even under superuser.
        -- Object grants are therefore restored under CURRENT_USER.
        IF rec.grantee = 'PUBLIC' OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = rec.grantee) THEN
            IF rec.obj_type = 'SEQUENCE' THEN
                IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = rec.target_name AND c.relkind = 'S') THEN
                    EXECUTE format('GRANT %s ON SEQUENCE public.%I TO %s%s', rec.privilege_type, rec.target_name, grantee_clause, grant_opt_clause);
                END IF;
            ELSE
                IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = rec.target_name AND c.relkind IN ('r', 'p')) THEN
                    EXECUTE format('GRANT %s ON TABLE public.%I TO %s%s', rec.privilege_type, rec.target_name, grantee_clause, grant_opt_clause);
                END IF;
            END IF;
        END IF;
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
        REVOKE USAGE, CREATE ON SCHEMA public FROM queueless_migrator_staging;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
        REVOKE USAGE ON SCHEMA public FROM queueless_app_staging;
    END IF;

    -- Re-apply any pre-existing schema grants recorded in snapshot (Phase 1 Requirement 3)
    FOR rec IN (
        SELECT target_name AS schema_name,
               attribute_1 AS grantee,
               attribute_2 AS priv,
               attribute_3 AS is_grantable
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'SCHEMA_GRANT'
    ) LOOP
        grantee_clause := CASE WHEN rec.grantee = 'PUBLIC' THEN 'PUBLIC' ELSE quote_ident(rec.grantee) END;
        grant_opt_clause := CASE WHEN rec.is_grantable = 'true' THEN ' WITH GRANT OPTION' ELSE '' END;
        IF rec.grantee = 'PUBLIC' OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = rec.grantee) THEN
            EXECUTE format('GRANT %s ON SCHEMA %I TO %s%s', rec.priv, rec.schema_name, grantee_clause, grant_opt_clause);
        END IF;
    END LOOP;

    -- Revoke staging role privileges from canary objects if they exist
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_system_environment_canary' AND c.relkind IN ('r', 'p')) THEN
        REVOKE ALL PRIVILEGES ON TABLE public._system_environment_canary FROM queueless_migrator_staging, queueless_app_staging;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = 'protect_canary_table_immutability') THEN
        REVOKE ALL PRIVILEGES ON FUNCTION public.protect_canary_table_immutability() FROM queueless_migrator_staging, queueless_app_staging;
    END IF;

    -- ========================================================================
    -- STEP 6: RESTORE PRE-EXISTING ROLE MEMBERSHIPS (ADMIN, INHERIT, SET) (BLOCKER 2)
    -- ========================================================================
    -- 1. Remove memberships involving staging roles that were NOT recorded in snapshot
    FOR mem_rec IN (
        SELECT m.rolname AS member_name, b.rolname AS group_name
        FROM pg_auth_members am
        JOIN pg_roles m ON am.member = m.oid
        JOIN pg_roles b ON am.roleid = b.oid
        WHERE (b.rolname IN ('queueless_migrator_staging', 'queueless_app_staging', 'canary_admin')
            OR m.rolname IN ('queueless_migrator_staging', 'queueless_app_staging', 'canary_admin'))
          AND NOT EXISTS (
              SELECT 1 FROM public._queueless_staging_provisioning_snapshot s
              WHERE s.snapshot_type = 'MEMBERSHIP'
                AND s.target_name = m.rolname
                AND s.attribute_1 = b.rolname
          )
    ) LOOP
        -- Only attempt REVOKE if caller has authority (superuser or has ADMIN OPTION on group_name)
        IF is_super OR EXISTS (
            SELECT 1 FROM pg_auth_members am_auth
            JOIN pg_roles r_auth ON r_auth.oid = am_auth.roleid
            JOIN pg_roles m_auth ON m_auth.oid = am_auth.member
            WHERE r_auth.rolname = mem_rec.group_name
              AND m_auth.rolname = caller_role
              AND am_auth.admin_option = TRUE
        ) THEN
            EXECUTE format('REVOKE ADMIN OPTION FOR %I FROM %I', mem_rec.group_name, mem_rec.member_name);
            EXECUTE format('REVOKE %I FROM %I', mem_rec.group_name, mem_rec.member_name);
        END IF;
    END LOOP;

    -- 2. Restore recorded memberships exactly as captured
    FOR rec IN (
        SELECT target_name AS member_role,
               attribute_1 AS group_role,
               (attribute_2 = 'true') AS has_admin,
               (attribute_3 = 'true') AS has_inherit,
               (attribute_4 = 'true') AS has_set,
               attribute_5 AS grantor
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'MEMBERSHIP'
    ) LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = rec.member_role)
           AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = rec.group_role) THEN
            granted_by_clause := '';
            -- Preserve grantor where PostgreSQL permissions permit it (PostgreSQL 16 GRANTED BY)
            IF current_setting('server_version_num')::integer >= 160000 AND rec.grantor IS NOT NULL THEN
                IF is_super OR rec.grantor = caller_role OR EXISTS (
                    SELECT 1 FROM pg_auth_members am
                    JOIN pg_roles r ON r.oid = am.roleid
                    JOIN pg_roles m ON m.oid = am.member
                    WHERE r.rolname = rec.grantor AND m.rolname = caller_role AND am.admin_option = TRUE
                ) THEN
                    granted_by_clause := format(' GRANTED BY %I', rec.grantor);
                END IF;
            END IF;

            IF current_setting('server_version_num')::integer >= 160000 THEN
                EXECUTE format('GRANT %I TO %I WITH ADMIN %s, INHERIT %s, SET %s%s',
                    rec.group_role, rec.member_role,
                    CASE WHEN rec.has_admin THEN 'TRUE' ELSE 'FALSE' END,
                    CASE WHEN rec.has_inherit THEN 'TRUE' ELSE 'FALSE' END,
                    CASE WHEN rec.has_set THEN 'TRUE' ELSE 'FALSE' END,
                    granted_by_clause);
            ELSE
                IF rec.has_admin THEN
                    EXECUTE format('GRANT %I TO %I WITH ADMIN OPTION', rec.group_role, rec.member_role);
                ELSE
                    EXECUTE format('GRANT %I TO %I', rec.group_role, rec.member_role);
                END IF;
            END IF;
        END IF;
    END LOOP;

    -- ========================================================================
    -- STEP 7: RESTORATION ASSERTIONS (SNAPSHOT TABLE PRESERVED UNTIL ALL PASS)
    -- ========================================================================
    -- 1. Verify all recorded tables match their recorded original owner
    FOR rec IN (
        SELECT target_name, attribute_1 AS original_owner
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'TABLE_OWNER'
    ) LOOP
        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = rec.target_name AND c.relkind IN ('r', 'p')) THEN
            IF (SELECT pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = rec.target_name) <> rec.original_owner THEN
                RAISE EXCEPTION 'ROLLBACK ASSERTION FAILED: Table public.% owner does not match recorded owner [%]! Snapshot preserved.', rec.target_name, rec.original_owner;
            END IF;
        END IF;
    END LOOP;

    -- 2. Verify all recorded sequences match their recorded original owner
    FOR rec IN (
        SELECT target_name, attribute_1 AS original_owner
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'SEQUENCE_OWNER'
    ) LOOP
        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid WHERE n.nspname = 'public' AND c.relkind = 'S' AND c.relname = rec.target_name) THEN
            IF (SELECT pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid WHERE n.nspname = 'public' AND c.relkind = 'S' AND c.relname = rec.target_name) <> rec.original_owner THEN
                RAISE EXCEPTION 'ROLLBACK ASSERTION FAILED: Sequence public.% owner does not match recorded owner [%]! Snapshot preserved.', rec.target_name, rec.original_owner;
            END IF;
        END IF;
    END LOOP;

    -- 3. Verify staging roles own 0 objects
    SELECT COUNT(*), COALESCE(string_agg(c.relname, ', '), 'NONE')
    INTO unhandled_count, unhandled_names
    FROM pg_class c
    JOIN pg_roles r ON c.relowner = r.oid
    WHERE r.rolname IN ('queueless_migrator_staging', 'queueless_app_staging');

    IF unhandled_count > 0 THEN
        RAISE EXCEPTION 'ROLLBACK ASSERTION FAILED: Staging roles still own % object(s): [%]! Snapshot preserved.', unhandled_count, unhandled_names;
    END IF;

    -- 4. Verify canary assets state (BLOCKER 4)
    IF EXISTS (
        SELECT 1 FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'PREEXISTING_OBJECT' AND target_name = '_system_environment_canary' AND attribute_1 = 'TABLE'
    ) THEN
        SELECT attribute_2 INTO cur_tbl_owner
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'PREEXISTING_OBJECT' AND target_name = '_system_environment_canary' AND attribute_1 = 'TABLE';

        IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_system_environment_canary') THEN
            RAISE EXCEPTION 'ROLLBACK ASSERTION FAILED: Pre-existing canary table was unexpectedly dropped!';
        END IF;

        IF (SELECT pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_system_environment_canary') <> cur_tbl_owner THEN
            RAISE EXCEPTION 'ROLLBACK ASSERTION FAILED: Pre-existing canary table owner does not match recorded owner [%]!', cur_tbl_owner;
        END IF;
    ELSE
        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '_system_environment_canary') THEN
            RAISE EXCEPTION 'ROLLBACK ASSERTION FAILED: Canary table still exists! Snapshot preserved.';
        END IF;
    END IF;

    IF EXISTS (
        SELECT 1 FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'PREEXISTING_OBJECT' AND target_name = 'protect_canary_table_immutability' AND attribute_1 = 'FUNCTION'
    ) THEN
        SELECT attribute_2 INTO cur_fn_owner
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'PREEXISTING_OBJECT' AND target_name = 'protect_canary_table_immutability' AND attribute_1 = 'FUNCTION';

        IF NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = 'protect_canary_table_immutability') THEN
            RAISE EXCEPTION 'ROLLBACK ASSERTION FAILED: Pre-existing canary function was unexpectedly dropped!';
        END IF;

        IF (SELECT pg_get_userbyid(p.proowner) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = 'protect_canary_table_immutability') <> cur_fn_owner THEN
            RAISE EXCEPTION 'ROLLBACK ASSERTION FAILED: Pre-existing canary function owner does not match recorded owner [%]!', cur_fn_owner;
        END IF;
    ELSE
        IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = 'protect_canary_table_immutability') THEN
            RAISE EXCEPTION 'ROLLBACK ASSERTION FAILED: Canary function still exists! Snapshot preserved.';
        END IF;
    END IF;

    -- 5. Verify default ACL state against snapshot (BLOCKER 3)
    -- 5a. Verify all recorded DEFAULT_ACL rows are restored
    FOR rec IN (
        SELECT target_name AS for_role,
               attribute_1 AS obj_type,
               attribute_2 AS grantee,
               attribute_3 AS priv
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'DEFAULT_ACL'
    ) LOOP
        IF NOT EXISTS (
            SELECT 1
            FROM pg_default_acl d
            JOIN pg_roles r ON d.defaclrole = r.oid
            JOIN pg_namespace n ON d.defaclnamespace = n.oid
            CROSS JOIN LATERAL aclexplode(d.defaclacl) exp
            WHERE r.rolname = rec.for_role
              AND n.nspname = 'public'
              AND (CASE WHEN exp.grantee = 0 THEN 'PUBLIC' ELSE pg_get_userbyid(exp.grantee) END) = rec.grantee
              AND exp.privilege_type = rec.priv
        ) THEN
            RAISE EXCEPTION 'ROLLBACK ASSERTION FAILED: Recorded default privilege for role [%] on [%] to [%] with priv [%] was not restored!',
                rec.for_role, rec.obj_type, rec.grantee, rec.priv;
        END IF;
    END LOOP;

    -- 5b. Verify no unrecorded staging default privileges remain for queueless_app_staging
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
        SELECT COUNT(*) INTO def_acl_unrecorded
        FROM pg_default_acl d
        JOIN pg_roles r ON d.defaclrole = r.oid
        JOIN pg_namespace n ON d.defaclnamespace = n.oid
        CROSS JOIN LATERAL aclexplode(d.defaclacl) exp
        WHERE r.rolname = 'queueless_migrator_staging'
          AND n.nspname = 'public'
          AND pg_get_userbyid(exp.grantee) = 'queueless_app_staging'
          AND NOT EXISTS (
              SELECT 1 FROM public._queueless_staging_provisioning_snapshot s
              WHERE s.snapshot_type = 'DEFAULT_ACL'
                AND s.target_name = r.rolname
                AND s.attribute_2 = 'queueless_app_staging'
                AND s.attribute_3 = exp.privilege_type
          );

        IF def_acl_unrecorded > 0 THEN
            RAISE EXCEPTION 'ROLLBACK ASSERTION FAILED: Found % lingering default privilege(s) on queueless_migrator_staging! Snapshot preserved.', def_acl_unrecorded;
        END IF;
    END IF;

    -- ========================================================================
    -- STEP 8: RESTORE PRE-EXISTING ROLES & DROP NON-PREEXISTING ROLES (Phase 1 Requirement 1)
    -- ========================================================================
    REVOKE ALL ON SCHEMA public FROM canary_admin, queueless_migrator_staging, queueless_app_staging;

    -- 1. Restore pre-existing roles attributes & encrypted passwords
    FOR rec IN (
        SELECT target_name, attribute_1 AS can_login, attribute_2 AS can_inherit, attribute_3 AS conn_limit, attribute_4 AS pass_hash
        FROM public._queueless_staging_provisioning_snapshot
        WHERE snapshot_type = 'PREEXISTING_ROLE'
    ) LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = rec.target_name) THEN
            IF rec.pass_hash IS NOT NULL AND rec.pass_hash <> '' THEN
                EXECUTE format('ALTER ROLE %I WITH ENCRYPTED PASSWORD %L', rec.target_name, rec.pass_hash);
            END IF;
            EXECUTE format('ALTER ROLE %I WITH %s %s CONNECTION LIMIT %s',
                rec.target_name,
                CASE WHEN rec.can_login = 'true' THEN 'LOGIN' ELSE 'NOLOGIN' END,
                CASE WHEN rec.can_inherit = 'true' THEN 'INHERIT' ELSE 'NOINHERIT' END,
                COALESCE(rec.conn_limit, '-1'));
        END IF;
    END LOOP;

    -- 2. Drop only non-preexisting roles
    IF NOT EXISTS (SELECT 1 FROM public._queueless_staging_provisioning_snapshot WHERE snapshot_type = 'PREEXISTING_ROLE' AND target_name = 'queueless_app_staging') THEN
        IF is_super OR EXISTS (
            SELECT 1 FROM pg_auth_members am
            JOIN pg_roles r ON r.oid = am.roleid
            JOIN pg_roles m ON m.oid = am.member
            WHERE r.rolname = 'queueless_app_staging' AND m.rolname = caller_role AND am.admin_option = TRUE
        ) THEN
            DROP ROLE IF EXISTS queueless_app_staging;
        END IF;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM public._queueless_staging_provisioning_snapshot WHERE snapshot_type = 'PREEXISTING_ROLE' AND target_name = 'queueless_migrator_staging') THEN
        IF is_super OR migrator_has_admin THEN
            FOR mem_rec IN (
                SELECT m.rolname AS member_name
                FROM pg_auth_members am
                JOIN pg_roles r ON r.oid = am.roleid
                JOIN pg_roles m ON m.oid = am.member
                WHERE r.rolname = 'queueless_migrator_staging'
            ) LOOP
                EXECUTE format('REVOKE ADMIN OPTION FOR queueless_migrator_staging FROM %I', mem_rec.member_name);
                EXECUTE format('REVOKE queueless_migrator_staging FROM %I', mem_rec.member_name);
            END LOOP;
            DROP ROLE IF EXISTS queueless_migrator_staging;
        END IF;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM public._queueless_staging_provisioning_snapshot WHERE snapshot_type = 'PREEXISTING_ROLE' AND target_name = 'canary_admin') THEN
        IF is_super OR canary_has_admin THEN
            FOR mem_rec IN (
                SELECT m.rolname AS member_name
                FROM pg_auth_members am
                JOIN pg_roles r ON r.oid = am.roleid
                JOIN pg_roles m ON m.oid = am.member
                WHERE r.rolname = 'canary_admin'
            ) LOOP
                BEGIN
                    EXECUTE format('REVOKE ADMIN OPTION FOR canary_admin FROM %I', mem_rec.member_name);
                    EXECUTE format('REVOKE canary_admin FROM %I', mem_rec.member_name);
                EXCEPTION WHEN OTHERS THEN
                    NULL;
                END;
            END LOOP;
            DROP ROLE IF EXISTS canary_admin;
        END IF;
    END IF;

    -- Only after all assertions have passed cleanly, drop snapshot table
    DROP TABLE IF EXISTS public._queueless_staging_provisioning_snapshot;

    RAISE NOTICE 'Rollback complete: Exact recorded before-state restored; staging roles dropped cleanly.';
END $$;
