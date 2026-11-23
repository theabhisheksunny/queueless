-- =============================================================================
-- QueueLess Controlled Database Environment Canary Bootstrap
-- File: scripts/bootstrap_database_canary.sql
--
-- MUST BE EXECUTED ONCE BY DATABASE ADMINISTRATOR (superuser / supabase_admin)
-- prior to deploying staging or production Cloud Run containers.
--
-- Security Architecture & Administrative Boundaries:
-- 1. Tier 1 (Application Runtime & Migration Identities):
--    - queueless_app_* (DML only) and queueless_migrator_* (DDL).
--    - Non-superusers (NOSUPERUSER, NOCREATEROLE, NOBYPASSRLS).
--    - Receive strictly SELECT-ONLY on public._system_environment_canary.
--    - Cannot ALTER, DROP, TRUNCATE, or disable triggers because table and function
--      are owned by canary_admin and these roles are NOT members of canary_admin.
-- 2. Tier 2 (Database Administrators, e.g. postgres in Supabase):
--    - Non-superuser administrative role with CREATEROLE and CREATEDB.
--    - Automatically granted canary_admin membership upon role creation in PG 16;
--      this script explicitly revokes that membership to enforce least privilege.
-- 3. Tier 3 (PostgreSQL Superusers, e.g. supabase_admin):
--    - Trusted administrative root principals. In PostgreSQL, superusers inherently
--      bypass table ownership, trigger enforcement, and permission checks.
--    - Superusers are trusted administrative principals; this script does not
--      claim to technically prevent a superuser from modifying the database.
-- =============================================================================

-- 1. Create Dedicated Administrative Role (Non-login, separate from runtime user)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'canary_admin') THEN
        CREATE ROLE canary_admin NOLOGIN;
    END IF;
END
$$;

-- 1b. Revoke Automatic Membership and ADMIN OPTION on canary_admin
-- In PostgreSQL 16+, CREATE ROLE automatically grants the new role to the executing role WITH ADMIN OPTION.
-- To ensure strict isolation so that NO login role can execute "SET ROLE canary_admin;",
-- we explicitly revoke membership and ADMIN OPTION from the executing user and postgres.
-- Revocation failures will abort the script immediately (no exception suppression).
DO $$
DECLARE
    creator TEXT := CURRENT_USER;
BEGIN
    -- Revoke ADMIN OPTION and membership from creator if not postgres
    IF creator <> 'postgres' THEN
        EXECUTE format('REVOKE ADMIN OPTION FOR canary_admin FROM %I, postgres', creator);
        EXECUTE format('REVOKE canary_admin FROM %I, postgres', creator);
    ELSE
        REVOKE ADMIN OPTION FOR canary_admin FROM postgres;
        REVOKE canary_admin FROM postgres;
    END IF;

    -- Also revoke from application/migrator roles if they pre-exist
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
        REVOKE ADMIN OPTION FOR canary_admin FROM queueless_app_staging;
        REVOKE canary_admin FROM queueless_app_staging;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
        REVOKE ADMIN OPTION FOR canary_admin FROM queueless_migrator_staging;
        REVOKE canary_admin FROM queueless_migrator_staging;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_app_prod') THEN
        REVOKE ADMIN OPTION FOR canary_admin FROM queueless_app_prod;
        REVOKE canary_admin FROM queueless_app_prod;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_migrator_prod') THEN
        REVOKE ADMIN OPTION FOR canary_admin FROM queueless_migrator_prod;
        REVOKE canary_admin FROM queueless_migrator_prod;
    END IF;

    RAISE NOTICE 'Revocation completed: canary_admin membership and ADMIN OPTION revoked from % and postgres.', creator;
END $$;

-- 2. Create Controlled Canary Table
CREATE TABLE IF NOT EXISTS public._system_environment_canary (
    id INT PRIMARY KEY DEFAULT 1,
    environment_name VARCHAR(50) NOT NULL CHECK (environment_name IN ('STAGING', 'PRODUCTION')),
    supabase_project_ref VARCHAR(100) NOT NULL,
    initialized_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_canary_singleton CHECK (id = 1)
);

-- 3. Seed Singleton Environment Record (Choose STAGING or PRODUCTION)
-- Uses ON CONFLICT (id) DO NOTHING to guarantee existing records are never silently overwritten.
--
-- For STAGING database:
-- INSERT INTO public._system_environment_canary (id, environment_name, supabase_project_ref)
-- VALUES (1, 'STAGING', 'YOUR_STAGING_SUPABASE_PROJECT_REF')
-- ON CONFLICT (id) DO NOTHING;
--
-- For PRODUCTION database:
-- INSERT INTO public._system_environment_canary (id, environment_name, supabase_project_ref)
-- VALUES (1, 'PRODUCTION', 'YOUR_PROD_SUPABASE_PROJECT_REF')
-- ON CONFLICT (id) DO NOTHING;

-- 4. Create Immutability Trigger Function
-- Removed SECURITY DEFINER: function only raises an exception and requires zero elevated privileges.
-- search_path is pinned to pg_catalog, pg_temp to eliminate search-path injection vectors.
CREATE OR REPLACE FUNCTION public.protect_canary_table_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'CANARY TAMPER VIOLATION: public._system_environment_canary is strictly immutable! DML modification and truncation are permanently blocked.';
END;
$$;

-- 5. Attach BEFORE Triggers to Block All DML and TRUNCATE Operations
DROP TRIGGER IF EXISTS trg_protect_canary_dml ON public._system_environment_canary;
CREATE TRIGGER trg_protect_canary_dml
BEFORE INSERT OR UPDATE OR DELETE ON public._system_environment_canary
FOR EACH ROW EXECUTE FUNCTION public.protect_canary_table_immutability();

DROP TRIGGER IF EXISTS trg_protect_canary_truncate ON public._system_environment_canary;
CREATE TRIGGER trg_protect_canary_truncate
BEFORE TRUNCATE ON public._system_environment_canary
FOR EACH STATEMENT EXECUTE FUNCTION public.protect_canary_table_immutability();

-- 6. Enforce Administrative Ownership
ALTER TABLE public._system_environment_canary OWNER TO canary_admin;
ALTER FUNCTION public.protect_canary_table_immutability() OWNER TO canary_admin;

-- 7. Configure Strict Least-Privilege Role Grants (SELECT-ONLY for runtime user)
-- Restrict Trigger Function EXECUTE privileges (revoke from PUBLIC)
REVOKE ALL ON FUNCTION public.protect_canary_table_immutability() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.protect_canary_table_immutability() TO canary_admin, postgres;

REVOKE ALL ON TABLE public._system_environment_canary FROM PUBLIC, anon, authenticated, service_role;

-- Revoke write and DDL privileges from runtime user role (postgres in Supabase)
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE public._system_environment_canary FROM postgres;

-- Grant strictly SELECT privilege to application runtime connection role
GRANT SELECT ON TABLE public._system_environment_canary TO postgres;

-- 8. Post-Bootstrap Privilege Isolation Assertions (Fails closed if isolation incomplete)
DO $$
DECLARE
    active_members INT;
    member_list TEXT;
    canary_table_owner TEXT;
    canary_func_owner TEXT;
BEGIN
    -- Check pg_auth_members: canary_admin MUST have zero members
    SELECT COUNT(*), COALESCE(string_agg(m.rolname, ', '), 'NONE')
    INTO active_members, member_list
    FROM pg_auth_members am
    JOIN pg_roles r ON r.oid = am.roleid
    JOIN pg_roles m ON m.oid = am.member
    WHERE r.rolname = 'canary_admin';

    IF active_members > 0 THEN
        RAISE EXCEPTION 'FATAL PRIVILEGE ISOLATION FAILURE: canary_admin still has % active member(s): [%]! Execute REVOKE canary_admin FROM <role> immediately.', active_members, member_list;
    END IF;

    -- Verify table owner is canary_admin
    SELECT tableowner INTO canary_table_owner
    FROM pg_tables
    WHERE schemaname = 'public' AND tablename = '_system_environment_canary';

    IF canary_table_owner IS DISTINCT FROM 'canary_admin' THEN
        RAISE EXCEPTION 'FATAL POST-BOOTSTRAP CHECK: public._system_environment_canary is owned by [%], expected [canary_admin]!', canary_table_owner;
    END IF;

    -- Verify trigger function owner is canary_admin
    SELECT r.rolname INTO canary_func_owner
    FROM pg_proc p
    JOIN pg_roles r ON r.oid = p.proowner
    WHERE p.proname = 'protect_canary_table_immutability';

    IF canary_func_owner IS DISTINCT FROM 'canary_admin' THEN
        RAISE EXCEPTION 'FATAL POST-BOOTSTRAP CHECK: protect_canary_table_immutability() is owned by [%], expected [canary_admin]!', canary_func_owner;
    END IF;

    RAISE NOTICE 'CANARY BOOTSTRAP PRIVILEGE ISOLATION VERIFIED: canary_admin has 0 members and solely owns canary assets.';
END $$;

-- =============================================================================
-- SQL Privilege & Immutability Verification Queries (Audit Suite)
-- =============================================================================

-- Query 1: Verify Table Owner is NOT the runtime application user
-- Expected: tableowner = 'canary_admin'
SELECT schemaname, tablename, tableowner
FROM pg_tables
WHERE schemaname = 'public' AND tablename = '_system_environment_canary';

-- Query 2: Verify Runtime Role Grants (MUST show ONLY SELECT)
-- Expected: grantee = 'postgres', privilege_type = 'SELECT' (NO INSERT/UPDATE/DELETE/TRUNCATE)
SELECT grantee, privilege_type, is_grantable
FROM information_schema.role_table_grants
WHERE table_schema = 'public' AND table_name = '_system_environment_canary';

-- Query 3: Verify Immutability Triggers are Active
-- Expected: trg_protect_canary_dml, trg_protect_canary_truncate
SELECT trigger_name, event_manipulation, action_timing, action_orientation
FROM information_schema.triggers
WHERE event_object_schema = 'public' AND event_object_table = '_system_environment_canary';

-- Query 4: Verify Single Record Integrity
-- Expected: exactly 1 row with id=1, correct environment_name, correct project_ref
SELECT id, environment_name, supabase_project_ref, initialized_at
FROM public._system_environment_canary;

-- Query 5: Verify Trigger Function Security Configuration
-- Expected: prosecdef = false (no security definer), proconfig containing search_path=pg_catalog, pg_temp
SELECT proname, prosecdef, proconfig
FROM pg_proc
WHERE proname = 'protect_canary_table_immutability';

-- Query 6: Verify Zero Membership in canary_admin (Must return 0 rows)
-- Expected: 0 rows (proves neither postgres, app, nor migrator can execute SET ROLE canary_admin)
SELECT r.rolname AS target_role, m.rolname AS member_role, am.admin_option
FROM pg_auth_members am
JOIN pg_roles r ON r.oid = am.roleid
JOIN pg_roles m ON m.oid = am.member
WHERE r.rolname = 'canary_admin';
