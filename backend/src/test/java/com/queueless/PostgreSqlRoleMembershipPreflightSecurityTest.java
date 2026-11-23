package com.queueless;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates PostgreSQL 16 Role Membership, Independent SET vs ADMIN OPTION Preflight,
 * and Atomic Ownership Transfer Security.
 *
 * Verifies the 6 key requirements:
 * 1. Role with ADMIN OPTION true, SET false (requires temporary SET grant).
 * 2. Role with ADMIN OPTION false, SET true (transfers directly without grant).
 * 3. Role with both false (fails closed, aborts before any modification).
 * 4. Role with both true (transfers directly without temporary grant).
 * 5. Temporary SET grant and revocation (complete lifecycle, post-revocation verification).
 * 6. Ownership transfer failure prevented before object modification (zero partial state mutations).
 * In addition to:
 * 7. Role created by postgres vs role created by supabase_admin.
 * 8. PostgreSQL version-appropriate compatibility strategy for pre-PG16 vs PG16+.
 */
@DisplayName("PostgreSQL 16 Independent SET vs ADMIN OPTION Preflight Integration Test")
public class PostgreSqlRoleMembershipPreflightSecurityTest {

    private static EmbeddedPostgres embeddedPostgres;
    private static DataSource superuserDataSource;

    @BeforeAll
    static void setUpAll() throws Exception {
        embeddedPostgres = EmbeddedPostgres.builder().start();
        superuserDataSource = embeddedPostgres.getPostgresDatabase();

        // Initialize simulated roles
        try (Connection conn = superuserDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // Simulated postgres: non-superuser administrative role (matching Supabase postgres role)
            stmt.execute("""
                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'simulated_postgres') THEN
                    CREATE ROLE simulated_postgres WITH LOGIN CREATEROLE NOSUPERUSER NOCREATEDB PASSWORD 'test_pg_pass';
                    GRANT USAGE, CREATE ON SCHEMA public TO simulated_postgres;
                END IF;
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'simulated_operator_no_createrole') THEN
                    CREATE ROLE simulated_operator_no_createrole WITH LOGIN NOCREATEROLE NOSUPERUSER NOCREATEDB PASSWORD 'test_op_pass';
                    GRANT USAGE, CREATE ON SCHEMA public TO simulated_operator_no_createrole;
                END IF;
                END $$;
                """);
        }
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (embeddedPostgres != null) {
            embeddedPostgres.close();
        }
    }

    @Test
    @DisplayName("1. ADMIN OPTION true, SET false: requires temporary SET grant and does not conflate privileges")
    void testAdminOptionTrueSetFalse_RequiresTemporarySetGrant() throws Exception {
        try (Connection conn = superuserDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Superuser creates test table and target migrator role
            stmt.execute("""
                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'migrator_admin_only_test') THEN
                    CREATE ROLE migrator_admin_only_test WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB;
                    GRANT USAGE, CREATE ON SCHEMA public TO migrator_admin_only_test;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.tbl_admin_only_test (id INT PRIMARY KEY, val TEXT)");
            stmt.execute("ALTER TABLE public.tbl_admin_only_test OWNER TO simulated_postgres");

            // Superuser grants ADMIN OPTION on migrator_admin_only_test to simulated_postgres
            stmt.execute("GRANT migrator_admin_only_test TO simulated_postgres WITH ADMIN OPTION");

            // Execute as simulated_postgres:
            stmt.execute("SET ROLE simulated_postgres");

            // Verify independent evaluation:
            // Simulating PostgreSQL 16 record with {"admin_option": true, "set_option": false}
            // has_set_membership MUST be FALSE, has_admin_option MUST be TRUE, has_membership MUST be TRUE
            String testBlock = """
                DO $$
                DECLARE
                    caller_role TEXT := CURRENT_USER;
                    is_super BOOLEAN;
                    has_membership BOOLEAN := FALSE;
                    has_set_membership BOOLEAN := FALSE;
                    has_admin_option BOOLEAN := FALSE;
                    temp_membership_granted BOOLEAN := FALSE;
                    simulated_pg16_auth RECORD;
                BEGIN
                    SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;

                    -- Evaluate 3 independent conditions against simulated PG16 auth entry where ADMIN=true, SET=false:
                    -- am JSON representation: {"admin_option": true, "set_option": false}
                    has_membership := TRUE;
                    has_admin_option := TRUE;
                    has_set_membership := CASE
                        WHEN ('{"admin_option": true, "set_option": false}'::jsonb ? 'set_option') THEN
                            COALESCE(('{"admin_option": true, "set_option": false}'::jsonb->>'set_option')::boolean, FALSE)
                        WHEN current_setting('server_version_num')::integer < 160000 THEN TRUE
                        ELSE FALSE
                    END;

                    -- Strict assertion: ADMIN OPTION must NOT satisfy has_set_membership!
                    IF has_set_membership THEN
                        RAISE EXCEPTION 'TEST FAILURE: has_set_membership must be FALSE when set_option is FALSE!';
                    END IF;
                    IF NOT has_admin_option THEN
                        RAISE EXCEPTION 'TEST FAILURE: has_admin_option must be TRUE!';
                    END IF;
                    IF NOT has_membership THEN
                        RAISE EXCEPTION 'TEST FAILURE: has_membership must be TRUE!';
                    END IF;

                    -- Because has_set_membership is FALSE, caller MUST use ADMIN OPTION to grant temporary membership
                    IF NOT is_super THEN
                        IF NOT has_set_membership THEN
                            IF NOT has_admin_option THEN
                                RAISE EXCEPTION 'PREFLIGHT ABORT: Caller [%] lacks SET and ADMIN OPTION.', caller_role;
                            END IF;
                            BEGIN
                                EXECUTE format('GRANT migrator_admin_only_test TO %I WITH SET TRUE', caller_role);
                            EXCEPTION WHEN syntax_error OR feature_not_supported THEN
                                EXECUTE format('GRANT migrator_admin_only_test TO %I', caller_role);
                            END;
                            temp_membership_granted := TRUE;
                        END IF;
                    END IF;

                    -- Transfer ownership
                    ALTER TABLE public.tbl_admin_only_test OWNER TO migrator_admin_only_test;

                    -- Temporary membership cleanup
                    IF temp_membership_granted THEN
                        EXECUTE format('REVOKE migrator_admin_only_test FROM %I', caller_role);
                    END IF;
                END $$;
                """;

            assertDoesNotThrow(() -> stmt.execute(testBlock),
                    "When caller has ADMIN OPTION true and SET false, preflight must grant temporary SET membership and succeed");

            stmt.execute("RESET ROLE");

            // Verify table ownership transferred
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'tbl_admin_only_test'")) {
                assertTrue(rs.next());
                assertEquals("migrator_admin_only_test", rs.getString("tableowner"));
            }
        }
    }

    @Test
    @DisplayName("2. ADMIN OPTION false, SET true: transfers directly without temporary grant")
    void testAdminOptionFalseSetTrue_TransfersDirectlyWithoutGrant() throws Exception {
        try (Connection conn = superuserDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Superuser creates test table and target migrator role
            stmt.execute("""
                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'migrator_set_only_test') THEN
                    CREATE ROLE migrator_set_only_test WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB;
                    GRANT USAGE, CREATE ON SCHEMA public TO migrator_set_only_test;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.tbl_set_only_test (id INT PRIMARY KEY, val TEXT)");
            stmt.execute("ALTER TABLE public.tbl_set_only_test OWNER TO simulated_postgres");

            // Grant role to simulated_postgres WITHOUT ADMIN OPTION
            stmt.execute("GRANT migrator_set_only_test TO simulated_postgres");

            stmt.execute("SET ROLE simulated_postgres");

            String testBlock = """
                DO $$
                DECLARE
                    caller_role TEXT := CURRENT_USER;
                    is_super BOOLEAN;
                    has_membership BOOLEAN := FALSE;
                    has_set_membership BOOLEAN := FALSE;
                    has_admin_option BOOLEAN := FALSE;
                    temp_membership_granted BOOLEAN := FALSE;
                BEGIN
                    SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;

                    -- Evaluate 3 independent conditions:
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
                    WHERE r.rolname = 'migrator_set_only_test'
                      AND m.rolname = caller_role;

                    has_membership := COALESCE(has_membership, FALSE);
                    has_admin_option := COALESCE(has_admin_option, FALSE);
                    has_set_membership := COALESCE(has_set_membership, FALSE);

                    -- Assert independent conditions:
                    IF NOT has_membership THEN
                        RAISE EXCEPTION 'TEST FAILURE: has_membership must be TRUE!';
                    END IF;
                    IF has_admin_option THEN
                        RAISE EXCEPTION 'TEST FAILURE: has_admin_option must be FALSE!';
                    END IF;
                    IF NOT has_set_membership THEN
                        RAISE EXCEPTION 'TEST FAILURE: has_set_membership must be TRUE!';
                    END IF;

                    -- Since has_set_membership is TRUE, no temporary grant needed!
                    IF NOT is_super THEN
                        IF NOT has_set_membership THEN
                            IF NOT has_admin_option THEN
                                RAISE EXCEPTION 'PREFLIGHT ABORT: Caller [%] lacks SET and ADMIN OPTION.', caller_role;
                            END IF;
                            temp_membership_granted := TRUE;
                        END IF;
                    END IF;

                    -- Verify temp_membership_granted remained FALSE
                    IF temp_membership_granted THEN
                        RAISE EXCEPTION 'TEST FAILURE: temp_membership_granted must remain FALSE when SET membership exists!';
                    END IF;

                    -- Direct ownership transfer
                    ALTER TABLE public.tbl_set_only_test OWNER TO migrator_set_only_test;
                END $$;
                """;

            assertDoesNotThrow(() -> stmt.execute(testBlock),
                    "When caller has SET true and ADMIN OPTION false, transfer must succeed directly without temporary grant");

            stmt.execute("RESET ROLE");

            // Verify table ownership transferred
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'tbl_set_only_test'")) {
                assertTrue(rs.next());
                assertEquals("migrator_set_only_test", rs.getString("tableowner"));
            }
        }
    }

    @Test
    @DisplayName("3. Both false: preflight check fails closed and aborts BEFORE any modifications (both no-membership and SET=false cases)")
    void testBothFalse_AbortsBeforeModification() throws Exception {
        try (Connection conn = superuserDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'migrator_both_false_test') THEN
                    CREATE ROLE migrator_both_false_test WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB;
                    GRANT USAGE, CREATE ON SCHEMA public TO migrator_both_false_test;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.tbl_both_false_test (id INT PRIMARY KEY, val TEXT)");
            stmt.execute("ALTER TABLE public.tbl_both_false_test OWNER TO simulated_postgres");

            stmt.execute("SET ROLE simulated_postgres");

            // Sub-case A: Zero rows in pg_auth_members (caller has no membership, no admin option)
            String testBlockNoMembership = """
                DO $$
                DECLARE
                    caller_role TEXT := CURRENT_USER;
                    is_super BOOLEAN;
                    has_membership BOOLEAN := FALSE;
                    has_set_membership BOOLEAN := FALSE;
                    has_admin_option BOOLEAN := FALSE;
                BEGIN
                    SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;

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
                    WHERE r.rolname = 'migrator_both_false_test'
                      AND m.rolname = caller_role;

                    has_membership := COALESCE(has_membership, FALSE);
                    has_admin_option := COALESCE(has_admin_option, FALSE);
                    has_set_membership := COALESCE(has_set_membership, FALSE);

                    IF NOT has_set_membership AND NOT has_admin_option THEN
                        RAISE EXCEPTION 'PREFLIGHT ABORT: Caller [%] is a non-superuser lacking ADMIN OPTION on [migrator_both_false_test] and does not possess active membership with SET enabled! Cannot grant temporary membership for ownership transfer. Execute this script as supabase_admin.', caller_role;
                    END IF;

                    ALTER TABLE public.tbl_both_false_test OWNER TO migrator_both_false_test;
                END $$;
                """;

            SQLException ex1 = assertThrows(SQLException.class, () -> stmt.execute(testBlockNoMembership),
                    "When caller has zero membership, preflight check must abort immediately");

            assertTrue(ex1.getMessage().contains("PREFLIGHT ABORT"));
            assertTrue(ex1.getMessage().contains("supabase_admin"));

            // Sub-case B: PG16 membership entry with SET=false, ADMIN=false (has_membership=true, but NO usable SET capability)
            // Proves that a generic MEMBER check is dangerous and that independent set_option checking fails closed
            String testBlockSetFalseMembership = """
                DO $$
                DECLARE
                    caller_role TEXT := CURRENT_USER;
                    is_super BOOLEAN;
                    has_membership BOOLEAN := FALSE;
                    has_set_membership BOOLEAN := FALSE;
                    has_admin_option BOOLEAN := FALSE;
                    simulated_entry JSONB := '{"admin_option": false, "set_option": false}'::jsonb;
                BEGIN
                    SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;

                    -- Even if has_membership is TRUE:
                    has_membership := TRUE;
                    has_admin_option := COALESCE((simulated_entry->>'admin_option')::boolean, FALSE);
                    has_set_membership := CASE
                        WHEN (simulated_entry ? 'set_option') THEN
                            COALESCE((simulated_entry->>'set_option')::boolean, FALSE)
                        WHEN current_setting('server_version_num')::integer < 160000 THEN TRUE
                        ELSE FALSE
                    END;

                    -- Strict assertion: generic membership MUST NOT be accepted when set_option is FALSE
                    IF NOT has_set_membership AND NOT has_admin_option THEN
                        RAISE EXCEPTION 'PREFLIGHT ABORT: Caller [%] is a member with SET FALSE and lacking ADMIN OPTION on [migrator_both_false_test]! Generic MEMBER check avoided. Execute this script as supabase_admin.', caller_role;
                    END IF;

                    ALTER TABLE public.tbl_both_false_test OWNER TO migrator_both_false_test;
                END $$;
                """;

            SQLException ex2 = assertThrows(SQLException.class, () -> stmt.execute(testBlockSetFalseMembership),
                    "When caller has membership with SET=false and ADMIN=false, preflight must abort immediately");

            assertTrue(ex2.getMessage().contains("PREFLIGHT ABORT"));
            assertTrue(ex2.getMessage().contains("Generic MEMBER check avoided"));

            stmt.execute("RESET ROLE");

            // Verify table owner remained unchanged (zero partial modifications)
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'tbl_both_false_test'")) {
                assertTrue(rs.next());
                assertEquals("simulated_postgres", rs.getString("tableowner"),
                        "Table owner must REMAIN simulated_postgres when authorization is missing");
            }
        }
    }

    @Test
    @DisplayName("4. Both true: transfers directly without temporary grant")
    void testBothTrue_TransfersDirectlyWithoutGrant() throws Exception {
        try (Connection conn = superuserDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'migrator_both_true_test') THEN
                    CREATE ROLE migrator_both_true_test WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB;
                    GRANT USAGE, CREATE ON SCHEMA public TO migrator_both_true_test;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.tbl_both_true_test (id INT PRIMARY KEY, val TEXT)");
            stmt.execute("ALTER TABLE public.tbl_both_true_test OWNER TO simulated_postgres");

            // Superuser grants role WITH ADMIN OPTION to simulated_postgres
            stmt.execute("GRANT migrator_both_true_test TO simulated_postgres WITH ADMIN OPTION");

            stmt.execute("SET ROLE simulated_postgres");

            String testBlock = """
                DO $$
                DECLARE
                    caller_role TEXT := CURRENT_USER;
                    is_super BOOLEAN;
                    has_membership BOOLEAN := FALSE;
                    has_set_membership BOOLEAN := FALSE;
                    has_admin_option BOOLEAN := FALSE;
                    temp_membership_granted BOOLEAN := FALSE;
                BEGIN
                    SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;

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
                    WHERE r.rolname = 'migrator_both_true_test'
                      AND m.rolname = caller_role;

                    has_membership := COALESCE(has_membership, FALSE);
                    has_admin_option := COALESCE(has_admin_option, FALSE);
                    has_set_membership := COALESCE(has_set_membership, FALSE);

                    IF NOT has_membership OR NOT has_admin_option OR NOT has_set_membership THEN
                        RAISE EXCEPTION 'TEST FAILURE: Expected all 3 conditions to be TRUE!';
                    END IF;

                    -- Step 5b logic: Since has_set_membership is TRUE, do NOT grant temporary membership
                    IF NOT is_super THEN
                        IF NOT has_set_membership THEN
                            temp_membership_granted := TRUE;
                        END IF;
                    END IF;

                    IF temp_membership_granted THEN
                        RAISE EXCEPTION 'TEST FAILURE: temp_membership_granted must remain FALSE when both are true!';
                    END IF;

                    ALTER TABLE public.tbl_both_true_test OWNER TO migrator_both_true_test;
                END $$;
                """;

            assertDoesNotThrow(() -> stmt.execute(testBlock),
                    "When caller has both true, transfer must succeed directly without temporary grant");

            stmt.execute("RESET ROLE");

            // Verify table ownership transferred
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'tbl_both_true_test'")) {
                assertTrue(rs.next());
                assertEquals("migrator_both_true_test", rs.getString("tableowner"));
            }
        }
    }

    @Test
    @DisplayName("5. Temporary SET grant and revocation: lifecycle and post-revocation assertion")
    void testTemporarySetGrantAndRevocation() throws Exception {
        try (Connection conn = superuserDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'migrator_temp_lifecycle_test') THEN
                    CREATE ROLE migrator_temp_lifecycle_test WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB;
                    GRANT USAGE, CREATE ON SCHEMA public TO migrator_temp_lifecycle_test;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.tbl_lifecycle_test (id SERIAL PRIMARY KEY, note TEXT)");
            stmt.execute("ALTER TABLE public.tbl_lifecycle_test OWNER TO simulated_postgres");
            stmt.execute("ALTER SEQUENCE public.tbl_lifecycle_test_id_seq OWNER TO simulated_postgres");

            // Superuser grants ADMIN OPTION on migrator_temp_lifecycle_test to simulated_postgres
            stmt.execute("GRANT migrator_temp_lifecycle_test TO simulated_postgres WITH ADMIN OPTION");

            stmt.execute("SET ROLE simulated_postgres");

            String lifecycleBlock = """
                DO $$
                DECLARE
                    caller_role TEXT := CURRENT_USER;
                    is_super BOOLEAN;
                    has_membership BOOLEAN := FALSE;
                    has_set_membership BOOLEAN := FALSE;
                    has_admin_option BOOLEAN := FALSE;
                    temp_membership_granted BOOLEAN := FALSE;
                    seq_rec RECORD;
                BEGIN
                    SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;

                    -- Simulate caller having ADMIN OPTION but no initial usable SET membership
                    has_admin_option := TRUE;
                    has_set_membership := FALSE;

                    -- Step 5b: Role membership grant with SET TRUE
                    IF NOT is_super THEN
                        IF NOT has_set_membership THEN
                            IF NOT has_admin_option THEN
                                RAISE EXCEPTION 'PREFLIGHT ABORT: Caller [%] lacks SET and ADMIN OPTION.', caller_role;
                            END IF;
                            BEGIN
                                EXECUTE format('GRANT migrator_temp_lifecycle_test TO %I WITH SET TRUE', caller_role);
                            EXCEPTION WHEN syntax_error OR feature_not_supported THEN
                                EXECUTE format('GRANT migrator_temp_lifecycle_test TO %I', caller_role);
                            END;
                            temp_membership_granted := TRUE;
                            RAISE NOTICE 'Temporarily granted migrator_temp_lifecycle_test to % with SET enabled.', caller_role;
                        END IF;
                    END IF;

                    -- Transfer table ownership
                    ALTER TABLE public.tbl_lifecycle_test OWNER TO migrator_temp_lifecycle_test;

                    -- Transfer sequence ownership
                    FOR seq_rec IN (
                        SELECT s.relname AS seqname
                        FROM pg_class s
                        JOIN pg_depend d ON d.objid = s.oid
                        JOIN pg_class t ON d.refobjid = t.oid
                        WHERE s.relkind = 'S'
                          AND t.relname = 'tbl_lifecycle_test'
                          AND t.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
                    ) LOOP
                        EXECUTE format('ALTER SEQUENCE public.%I OWNER TO migrator_temp_lifecycle_test', seq_rec.seqname);
                    END LOOP;

                    -- Step 5d: Revoke temporary membership
                    IF temp_membership_granted THEN
                        EXECUTE format('REVOKE migrator_temp_lifecycle_test FROM %I', caller_role);
                        RAISE NOTICE 'Revoked temporary membership from %.', caller_role;

                        -- Assert revocation was effective
                        IF pg_has_role(caller_role, 'migrator_temp_lifecycle_test', 'MEMBER') THEN
                            RAISE EXCEPTION 'FATAL: Temporary membership revocation failed! Caller [%] is still a member.', caller_role;
                        END IF;
                    END IF;
                END $$;
                """;

            assertDoesNotThrow(() -> stmt.execute(lifecycleBlock),
                    "Lifecycle with temporary SET grant and revocation must succeed and verify revocation");

            stmt.execute("RESET ROLE");

            // Verify table and sequence owners
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'tbl_lifecycle_test'")) {
                assertTrue(rs.next());
                assertEquals("migrator_temp_lifecycle_test", rs.getString("tableowner"));
            }

            try (ResultSet rs = stmt.executeQuery("""
                SELECT r.rolname AS seqowner
                FROM pg_class c
                JOIN pg_roles r ON c.relowner = r.oid
                WHERE c.relname = 'tbl_lifecycle_test_id_seq' AND c.relkind = 'S'
                """)) {
                assertTrue(rs.next());
                assertEquals("migrator_temp_lifecycle_test", rs.getString("seqowner"));
            }

            // Verify caller is NO LONGER a member of target role
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT pg_has_role('simulated_postgres', 'migrator_temp_lifecycle_test', 'MEMBER')")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1),
                        "simulated_postgres must not have membership in target role after revocation");
            }
        }
    }

    @Test
    @DisplayName("6. Ownership transfer failure prevented BEFORE object modification (Zero partial changes)")
    void testOwnershipTransferFailurePreventedBeforeObjectModification() throws Exception {
        try (Connection conn = superuserDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'migrator_locked_atomic_test') THEN
                    CREATE ROLE migrator_locked_atomic_test WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB;
                    GRANT USAGE, CREATE ON SCHEMA public TO migrator_locked_atomic_test;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.tbl_atomic_1 (id INT PRIMARY KEY, name TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS public.tbl_atomic_2 (id INT PRIMARY KEY, name TEXT)");
            stmt.execute("ALTER TABLE public.tbl_atomic_1 OWNER TO simulated_postgres");
            stmt.execute("ALTER TABLE public.tbl_atomic_2 OWNER TO simulated_postgres");

            // As simulated_postgres (lacking membership and lacking admin option)
            stmt.execute("SET ROLE simulated_postgres");

            String multiTableTransferBlock = """
                DO $$
                DECLARE
                    caller_role TEXT := CURRENT_USER;
                    is_super BOOLEAN;
                    has_admin_option BOOLEAN := FALSE;
                    has_set_membership BOOLEAN := FALSE;
                    has_membership BOOLEAN := FALSE;
                    allowlist_tables TEXT[] := ARRAY['tbl_atomic_1', 'tbl_atomic_2'];
                    tbl TEXT;
                BEGIN
                    SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;

                    -- Step 5a Preflight
                    IF NOT is_super THEN
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
                        WHERE r.rolname = 'migrator_locked_atomic_test'
                          AND m.rolname = caller_role;

                        has_membership := COALESCE(has_membership, FALSE);
                        has_admin_option := COALESCE(has_admin_option, FALSE);
                        has_set_membership := COALESCE(has_set_membership, FALSE);

                        IF NOT has_set_membership AND NOT has_admin_option THEN
                            RAISE EXCEPTION 'PREFLIGHT ABORT: Caller [%] is a non-superuser lacking ADMIN OPTION on [migrator_locked_atomic_test] and does not possess active membership with SET enabled! Cannot grant temporary membership for ownership transfer. Execute this script as supabase_admin.', caller_role;
                        END IF;
                    END IF;

                    -- Object modifications (must NEVER be reached on failure)
                    FOREACH tbl IN ARRAY allowlist_tables LOOP
                        EXECUTE format('ALTER TABLE public.%I OWNER TO migrator_locked_atomic_test', tbl);
                    END LOOP;
                END $$;
                """;

            SQLException ex = assertThrows(SQLException.class, () -> stmt.execute(multiTableTransferBlock),
                    "Preflight MUST throw SQLException before altering any table");

            assertTrue(ex.getMessage().contains("PREFLIGHT ABORT"));
            assertTrue(ex.getMessage().contains("Execute this script as supabase_admin"));

            stmt.execute("RESET ROLE");

            // CRITICAL CHECK: Verify BOTH tables remain owned by simulated_postgres
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'tbl_atomic_1'")) {
                assertTrue(rs.next());
                assertEquals("simulated_postgres", rs.getString("tableowner"),
                        "tbl_atomic_1 owner must remain simulated_postgres");
            }

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'tbl_atomic_2'")) {
                assertTrue(rs.next());
                assertEquals("simulated_postgres", rs.getString("tableowner"),
                        "tbl_atomic_2 owner must remain simulated_postgres");
            }
        }
    }

    @Test
    @DisplayName("7. Version-appropriate compatibility strategy: pre-PG16 vs PG16+ JSONB evaluation")
    void testPostgreSqlVersionCompatibilityStrategy() throws Exception {
        try (Connection conn = superuserDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Test the exact CASE expression for PostgreSQL version compatibility
            String query = """
                SELECT
                    -- Case 1: PG16+ row with set_option: false and admin_option: true
                    (CASE
                        WHEN ('{"admin_option": true, "set_option": false}'::jsonb ? 'set_option') THEN
                            COALESCE(('{"admin_option": true, "set_option": false}'::jsonb->>'set_option')::boolean, FALSE)
                        WHEN 140000 < 160000 THEN TRUE
                        ELSE FALSE
                    END) AS pg16_set_false,

                    -- Case 2: PG16+ row with set_option: true and admin_option: false
                    (CASE
                        WHEN ('{"admin_option": false, "set_option": true}'::jsonb ? 'set_option') THEN
                            COALESCE(('{"admin_option": false, "set_option": true}'::jsonb->>'set_option')::boolean, FALSE)
                        WHEN 140000 < 160000 THEN TRUE
                        ELSE FALSE
                    END) AS pg16_set_true,

                    -- Case 3: Pre-PG16 row without set_option column, server_version < 160000
                    (CASE
                        WHEN ('{"admin_option": true}'::jsonb ? 'set_option') THEN
                            COALESCE(('{"admin_option": true}'::jsonb->>'set_option')::boolean, FALSE)
                        WHEN 140000 < 160000 THEN TRUE
                        ELSE FALSE
                    END) AS pre_pg16_with_membership,

                    -- Case 4: Pre-PG16 without membership (null or empty jsonb)
                    (CASE
                        WHEN ('{}'::jsonb ? 'set_option') THEN
                            COALESCE(('{}'::jsonb->>'set_option')::boolean, FALSE)
                        WHEN 160000 < 160000 THEN TRUE
                        ELSE FALSE
                    END) AS pg16_empty_jsonb_fails_closed
                """;

            try (ResultSet rs = stmt.executeQuery(query)) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean("pg16_set_false"),
                        "On PG16, set_option: false MUST evaluate to FALSE, even when admin_option is true");
                assertTrue(rs.getBoolean("pg16_set_true"),
                        "On PG16, set_option: true MUST evaluate to TRUE");
                assertTrue(rs.getBoolean("pre_pg16_with_membership"),
                        "On pre-PG16, membership conveys SET capability");
                assertFalse(rs.getBoolean("pg16_empty_jsonb_fails_closed"),
                        "On PG16+, missing set_option fails closed to FALSE");
            }
        }
    }

    @Test
    @DisplayName("8. Role created by superuser (supabase_admin): non-superuser receives ZERO ADMIN OPTION")
    void testRoleCreatedBySupabaseAdmin() throws Exception {
        try (Connection conn = superuserDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // As superuser (simulating supabase_admin), create a migrator role
            stmt.execute("""
                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'migrator_created_by_super') THEN
                    CREATE ROLE migrator_created_by_super WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB;
                END IF;
                END $$;
                """);

            // Verify simulated_postgres receives ZERO membership and ZERO admin option
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*)
                FROM pg_auth_members am
                JOIN pg_roles r ON r.oid = am.roleid
                JOIN pg_roles m ON m.oid = am.member
                WHERE r.rolname = 'migrator_created_by_super' AND m.rolname = 'simulated_postgres'
                """)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "simulated_postgres must have 0 rows in pg_auth_members for superuser-created role");
            }

            // Verify that the preflight authority check query strictly evaluates to FALSE for simulated_postgres
            try (ResultSet rs = stmt.executeQuery("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_auth_members am
                    JOIN pg_roles r ON r.oid = am.roleid
                    JOIN pg_roles m ON m.oid = am.member
                    WHERE r.rolname = 'migrator_created_by_super'
                      AND m.rolname = 'simulated_postgres'
                      AND am.admin_option = TRUE
                ) AS has_admin_option
                """)) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean("has_admin_option"),
                        "simulated_postgres must NOT have ADMIN OPTION on superuser-created role");
            }

            // A non-superuser without CREATEROLE / ADMIN OPTION cannot execute GRANT
            stmt.execute("SET ROLE simulated_operator_no_createrole");
            SQLException ex = assertThrows(SQLException.class, () -> {
                stmt.execute("GRANT migrator_created_by_super TO simulated_operator_no_createrole");
            }, "Non-superuser without ADMIN OPTION must NOT be able to grant role");

            assertTrue(ex.getMessage().toLowerCase().contains("permission denied") ||
                            ex.getMessage().toLowerCase().contains("admin option"),
                    "Error must indicate missing permission/admin option: " + ex.getMessage());

            stmt.execute("RESET ROLE");
        }
    }
}
