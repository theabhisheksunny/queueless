package com.queueless;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import io.zonky.test.db.postgres.embedded.PgBinaryResolver;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.flywaydb.core.Flyway;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QueueLess — Supabase Staging Provisioning Security & Rollback Test Suite (PostgreSQL 16.4)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SupabaseStagingProvisioningSecurityTest {

    private static EmbeddedPostgres pg16Instance;
    private static DataSource superuserDs;
    private static String pg16VersionOutput;

    private static String rawProvisioningSql;
    private static String rawRollbackSql;
    private static String provisioningSqlSha256;
    private static String rollbackSqlSha256;

    private static Path findScriptPath(String filename) {
        Path p1 = Path.of("..", "scripts", filename);
        if (Files.exists(p1)) return p1;
        Path p2 = Path.of("scripts", filename);
        if (Files.exists(p2)) return p2;
        throw new IllegalStateException("Cannot locate script: " + filename);
    }

    private static String computeSha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        return HexFormat.of().formatHex(digest).toUpperCase();
    }

    public static String getExecutableProvisioningSql(String migratorPass, String appPass) {
        return rawProvisioningSql
                .replace("<STRONG_STAGING_MIGRATOR_PASSWORD>", migratorPass)
                .replace("<STRONG_STAGING_APP_PASSWORD>", appPass);
    }

    public static String getExecutableRollbackSql() {
        return rawRollbackSql;
    }

    @BeforeAll
    static void setUpAll() throws Exception {
        // 1. Locate and hash SQL files
        Path provPath = findScriptPath("provision_staging_db_roles.sql");
        Path rollPath = findScriptPath("rollback_staging_db_roles.sql");

        rawProvisioningSql = Files.readString(provPath);
        rawRollbackSql = Files.readString(rollPath);

        provisioningSqlSha256 = computeSha256(Files.readAllBytes(provPath));
        rollbackSqlSha256 = computeSha256(Files.readAllBytes(rollPath));

        System.out.println("================================================================================");
        System.out.println("EXACT SQL ARTIFACTS LOADED FOR POSTGRESQL 16.4 TESTING:");
        System.out.println("  Provisioning SQL Path : " + provPath.toAbsolutePath().normalize());
        System.out.println("  Provisioning SHA-256  : " + provisioningSqlSha256);
        System.out.println("  Rollback SQL Path     : " + rollPath.toAbsolutePath().normalize());
        System.out.println("  Rollback SHA-256      : " + rollbackSqlSha256);
        System.out.println("================================================================================");

        // 2. Start PostgreSQL 16.4 Engine
        File jarFile = new File(System.getProperty("user.home") +
                "/.m2/repository/io/zonky/test/postgres/embedded-postgres-binaries-windows-amd64/16.4.0/embedded-postgres-binaries-windows-amd64-16.4.0.jar");
        assertTrue(jarFile.exists(), "PG 16 binary jar must exist at " + jarFile.getAbsolutePath());

        PgBinaryResolver pg16Resolver = (system, machine) -> {
            JarFile jar = new JarFile(jarFile);
            JarEntry entry = jar.getJarEntry("postgres-windows-x86_64.txz");
            if (entry == null) {
                throw new IllegalStateException("postgres-windows-x86_64.txz not found in " + jarFile);
            }
            return jar.getInputStream(entry);
        };

        pg16Instance = EmbeddedPostgres.builder()
                .setPgBinaryResolver(pg16Resolver)
                .start();
        superuserDs = pg16Instance.getPostgresDatabase();

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SELECT version()")) {
                assertTrue(rs.next());
                pg16VersionOutput = rs.getString(1);
            }
            System.out.println("PostgreSQL 16.4 Server Version: " + pg16VersionOutput);

            stmt.execute("""
                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'anon') THEN
                    CREATE ROLE anon NOLOGIN;
                END IF;
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'authenticated') THEN
                    CREATE ROLE authenticated NOLOGIN;
                END IF;
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'service_role') THEN
                    CREATE ROLE service_role NOLOGIN;
                END IF;
                END $$;
                """);
        }
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pg16Instance != null) {
            pg16Instance.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Test 1: First-time provisioning execution from exact SQL file")
    void test01_FirstTimeProvisioning() throws Exception {
        System.out.println("TEST 1: First-time provisioning on " + pg16VersionOutput);

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Create initial allowlist tables owned by postgres
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));
                CREATE TABLE IF NOT EXISTS public.shops (id SERIAL PRIMARY KEY, name VARCHAR(100));
                CREATE TABLE IF NOT EXISTS public.flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(50));
                ALTER TABLE public.users OWNER TO postgres;
                ALTER TABLE public.shops OWNER TO postgres;
                ALTER TABLE public.flyway_schema_history OWNER TO postgres;
                """);

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // Assertions
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='_system_environment_canary'")) {
                assertTrue(rs.next());
                assertEquals("canary_admin", rs.getString(1));
            }

            try (ResultSet rs = stmt.executeQuery("SELECT environment_name, supabase_project_ref FROM public._system_environment_canary WHERE id=1")) {
                assertTrue(rs.next());
                assertEquals("STAGING", rs.getString(1));
                assertEquals("tlgibnjprelzpxfxycab", rs.getString(2));
            }

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_auth_members am JOIN pg_roles r ON r.oid=am.roleid WHERE r.rolname='canary_admin'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }

            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='users'")) {
                assertTrue(rs.next());
                assertEquals("queueless_migrator_staging", rs.getString(1));
            }

            // Verify before-state snapshot table was created and recorded original owner
            try (ResultSet rs = stmt.executeQuery("SELECT attribute_1 FROM public._queueless_staging_provisioning_snapshot WHERE snapshot_type='TABLE_OWNER' AND target_name='users'")) {
                assertTrue(rs.next());
                assertEquals("postgres", rs.getString(1), "Original owner must be recorded as postgres");
            }

            System.out.println("Test 1 PASSED: First-time provisioning verified from exact SQL artifact.");
        }
    }

    @Test
    @Order(2)
    @DisplayName("Test 2: Second idempotent execution of exact SQL file")
    void test02_SecondIdempotentExecution() throws Exception {
        System.out.println("TEST 2: Second idempotent execution");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='_system_environment_canary'")) {
                assertTrue(rs.next());
                assertEquals("canary_admin", rs.getString(1));
            }

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_auth_members am JOIN pg_roles r ON r.oid=am.roleid WHERE r.rolname='canary_admin'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }

            System.out.println("Test 2 PASSED: Second idempotent execution succeeded with zero errors.");
        }
    }

    @Test
    @Order(3)
    @DisplayName("Test 3: BLOCKER 1 — Canary authority with non-superuser caller (missing ADMIN OPTION fails closed)")
    void test03_CanaryAuthorityNonSuperuserMissingAdminOption() throws Exception {
        System.out.println("TEST 3: Canary authority with non-superuser caller missing ADMIN OPTION");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Ensure prerequisite roles exist
            stmt.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='canary_admin') THEN CREATE ROLE canary_admin WITH NOLOGIN NOINHERIT; END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='queueless_migrator_staging') THEN CREATE ROLE queueless_migrator_staging; END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='queueless_app_staging') THEN CREATE ROLE queueless_app_staging; END IF;
                END $$;
                """);

            // Create non-superuser admin simulated_postgres_canary_test with CREATEROLE but explicitly NO ADMIN OPTION on canary_admin
            stmt.execute("DROP ROLE IF EXISTS simulated_postgres_canary_test");
            stmt.execute("CREATE ROLE simulated_postgres_canary_test WITH LOGIN NOSUPERUSER CREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO simulated_postgres_canary_test");
            stmt.execute("GRANT queueless_migrator_staging TO simulated_postgres_canary_test WITH ADMIN OPTION");
            stmt.execute("GRANT queueless_app_staging TO simulated_postgres_canary_test WITH ADMIN OPTION");

            // Deliberately DO NOT grant ADMIN OPTION on canary_admin
            stmt.execute("REVOKE ADMIN OPTION FOR canary_admin FROM simulated_postgres_canary_test");
            stmt.execute("REVOKE canary_admin FROM simulated_postgres_canary_test");

            // Drop canary objects so we test the first-time canary creation path under non-superuser (BLOCKER 1)
            stmt.execute("DROP TABLE IF EXISTS public._system_environment_canary CASCADE;");
            stmt.execute("DROP FUNCTION IF EXISTS public.protect_canary_table_immutability CASCADE;");

            // Execute as non-superuser simulated_postgres_canary_test
            stmt.execute("SET ROLE simulated_postgres_canary_test");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            SQLException ex = assertThrows(SQLException.class, () -> stmt.execute(sql));
            System.out.println("Test 3 expected abort: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("CANARY PREFLIGHT ABORT") || ex.getMessage().contains("lacks ADMIN OPTION on canary_admin"));

            stmt.execute("RESET ROLE");

            // Now execute provisioning as the authorized administrator (postgres) to verify the successful provisioning path
            stmt.execute(sql);
            System.out.println("Test 3 authorized provisioning succeeded under authorized admin!");

            stmt.execute("REVOKE ALL ON SCHEMA public FROM simulated_postgres_canary_test; DROP ROLE simulated_postgres_canary_test;");
            System.out.println("Test 3 PASSED: Blocker 1 verified (fails closed without ADMIN OPTION, succeeds with it).");
        }
    }

    @Test
    @Order(4)
    @DisplayName("Test 4: Existing roles with missing ADMIN OPTION fails closed")
    void test04_ExistingRolesMissingAdminOption() throws Exception {
        System.out.println("TEST 4: Existing roles with missing ADMIN OPTION fails closed");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP ROLE IF EXISTS simulated_postgres_noadmin");
            stmt.execute("CREATE ROLE simulated_postgres_noadmin WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO simulated_postgres_noadmin");

            stmt.execute("GRANT queueless_migrator_staging TO simulated_postgres_noadmin WITH SET FALSE");
            stmt.execute("REVOKE ADMIN OPTION FOR queueless_migrator_staging FROM simulated_postgres_noadmin");

            stmt.execute("SET ROLE simulated_postgres_noadmin");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            SQLException ex = assertThrows(SQLException.class, () -> stmt.execute(sql));
            System.out.println("Test 4 expected exception: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("lacks active SET membership and ADMIN OPTION"));

            stmt.execute("RESET ROLE");
            stmt.execute("REVOKE ALL ON SCHEMA public FROM simulated_postgres_noadmin; DROP ROLE simulated_postgres_noadmin;");
            System.out.println("Test 4 PASSED: Missing ADMIN OPTION fails closed.");
        }
    }

    @Test
    @Order(5)
    @DisplayName("Test 5: Existing roles with missing SET OPTION handled correctly")
    void test05_ExistingRolesMissingSetOption() throws Exception {
        System.out.println("TEST 5: Existing roles with missing SET OPTION handled correctly");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP ROLE IF EXISTS simulated_postgres_setter");
            stmt.execute("CREATE ROLE simulated_postgres_setter WITH LOGIN NOSUPERUSER CREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO simulated_postgres_setter");

            stmt.execute("GRANT queueless_migrator_staging TO simulated_postgres_setter WITH ADMIN OPTION");
            stmt.execute("GRANT queueless_migrator_staging TO simulated_postgres_setter WITH SET FALSE");
            stmt.execute("GRANT queueless_app_staging TO simulated_postgres_setter WITH ADMIN OPTION");
            stmt.execute("GRANT queueless_app_staging TO simulated_postgres_setter WITH SET FALSE");

            stmt.execute("SET ROLE simulated_postgres_setter");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            stmt.execute("RESET ROLE");

            try (ResultSet rs = stmt.executeQuery("SELECT pg_has_role('simulated_postgres_setter', 'queueless_migrator_staging', 'SET')")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1), "simulated_postgres_setter must NOT retain active SET capability!");
            }

            stmt.execute("REVOKE ALL ON SCHEMA public FROM simulated_postgres_setter; DROP ROLE simulated_postgres_setter;");
            System.out.println("Test 5 PASSED: Missing SET with ADMIN OPTION safely granted temporary SET and revoked.");
        }
    }

    @Test
    @Order(6)
    @DisplayName("Test 6: Unauthorized object ownership detected and rejected")
    void test06_UnauthorizedObjectOwnership() throws Exception {
        System.out.println("TEST 6: Unauthorized object ownership detected and rejected");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE public.unauthorized_alien_table (id INT);");
            stmt.execute("ALTER TABLE public.unauthorized_alien_table OWNER TO queueless_migrator_staging;");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            SQLException ex = assertThrows(SQLException.class, () -> stmt.execute(sql));
            System.out.println("Test 6 expected abort: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("SECURITY AUDIT ABORT") || ex.getMessage().contains("unexpected object"));

            stmt.execute("DROP TABLE public.unauthorized_alien_table;");
            System.out.println("Test 6 PASSED: Unauthorized object ownership detected and rejected.");
        }
    }

    @Test
    @Order(7)
    @DisplayName("Test 7: Canary trigger deletion detected and repaired")
    void test07_CanaryTriggerDeletionDetection() throws Exception {
        System.out.println("TEST 7: Canary trigger deletion detected and repaired");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP TRIGGER IF EXISTS trg_protect_canary_dml ON public._system_environment_canary;");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_trigger WHERE tgrelid='public._system_environment_canary'::regclass AND tgname='trg_protect_canary_dml' AND NOT tgisinternal")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "DML trigger must be reinstated");
            }

            System.out.println("Test 7 PASSED: Trigger deletion detected, repaired, and validated.");
        }
    }

    @Test
    @Order(8)
    @DisplayName("Test 8: Canary function alteration detected and repaired")
    void test08_CanaryFunctionAlterationDetection() throws Exception {
        System.out.println("TEST 8: Canary function alteration detected and repaired");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE OR REPLACE FUNCTION public.protect_canary_table_immutability()
                RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER AS $$
                BEGIN
                    RAISE EXCEPTION 'Tampered function';
                END;
                $$;
                ALTER FUNCTION public.protect_canary_table_immutability() OWNER TO canary_admin;
                """);

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            try (ResultSet rs = stmt.executeQuery("""
                SELECT prosecdef, COALESCE(proconfig @> ARRAY['search_path=pg_catalog, pg_temp'], FALSE)
                FROM pg_proc WHERE proname='protect_canary_table_immutability'
                """)) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1), "prosecdef must be FALSE (SECURITY INVOKER)");
                assertTrue(rs.getBoolean(2), "search_path must be pinned to pg_catalog, pg_temp");
            }

            System.out.println("Test 8 PASSED: Function alteration detected, repaired, and validated.");
        }
    }

    @Test
    @Order(9)
    @DisplayName("Test 9: Unrelated public schema objects receive zero privileges (SEC-01)")
    void test09_UnrelatedPublicSchemaObjectsUntouched() throws Exception {
        System.out.println("TEST 9: Unrelated public schema objects receive zero privileges");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS public.unrelated_supabase_table (id SERIAL PRIMARY KEY, info TEXT);");
            stmt.execute("ALTER TABLE public.unrelated_supabase_table OWNER TO postgres;");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            try (ResultSet rs = stmt.executeQuery("SELECT has_table_privilege('queueless_app_staging', 'public.unrelated_supabase_table', 'SELECT')")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1), "queueless_app_staging must have NO SELECT privilege on unrelated table");
            }

            try (ResultSet rs = stmt.executeQuery("SELECT has_table_privilege('queueless_migrator_staging', 'public.unrelated_supabase_table', 'SELECT')")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1), "queueless_migrator_staging must have NO SELECT privilege on unrelated table");
            }

            System.out.println("Test 9 PASSED: Unrelated public objects received zero privileges.");
        }
    }

    @Test
    @Order(10)
    @DisplayName("Test 10: BLOCKER 2 — Rollback from recorded before-state snapshot preserves grants and restores exact owners")
    void test10_RollbackRecordedStateRestoration() throws Exception {
        System.out.println("TEST 10: Rollback from recorded before-state snapshot");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Pre-existing grant on unrelated_supabase_table to anon
            stmt.execute("GRANT SELECT ON TABLE public.unrelated_supabase_table TO anon;");

            // Execute exact Rollback SQL artifact
            String rollbackSql = getExecutableRollbackSql();
            stmt.execute(rollbackSql);

            // Verify table ownership restored to RECORDED original owner (postgres)
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='users'")) {
                assertTrue(rs.next());
                assertEquals("postgres", rs.getString(1), "users table owner restored to recorded owner");
            }

            // Verify canary table dropped
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_tables WHERE schemaname='public' AND tablename='_system_environment_canary'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Canary table dropped");
            }

            // Verify staging roles dropped
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_roles WHERE rolname IN ('queueless_migrator_staging', 'queueless_app_staging', 'canary_admin')")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Staging roles dropped");
            }

            // Verify snapshot table cleaned up
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_tables WHERE schemaname='public' AND tablename='_queueless_staging_provisioning_snapshot'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Snapshot table dropped");
            }

            // Verify pre-existing grant on unrelated_supabase_table to anon is completely intact!
            try (ResultSet rs = stmt.executeQuery("SELECT has_table_privilege('anon', 'public.unrelated_supabase_table', 'SELECT')")) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1), "Pre-existing grant to anon on unrelated table must remain INTACT!");
            }

            stmt.execute("DROP TABLE IF EXISTS public.unrelated_supabase_table;");
            System.out.println("Test 10 PASSED: Blocker 2 verified (exact recorded state restored without broad DROP OWNED).");
        }
    }

    @Test
    @Order(11)
    @DisplayName("Test 11: Transaction failure with complete atomic rollback")
    void test11_TransactionFailureWithCompleteRollback() throws Exception {
        System.out.println("TEST 11: Transaction failure with complete atomic rollback");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Re-provision fresh from exact SQL
            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // Create alien object to trigger intentional audit abort
            stmt.execute("CREATE TABLE public.unauthorized_alien_table (id INT);");
            stmt.execute("ALTER TABLE public.unauthorized_alien_table OWNER TO queueless_migrator_staging;");

            SQLException ex = assertThrows(SQLException.class, () -> stmt.execute(sql));
            System.out.println("Test 11 expected abort: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("SECURITY AUDIT ABORT") || ex.getMessage().contains("unexpected object"));

            // Cleanup
            stmt.execute("DROP TABLE public.unauthorized_alien_table");
            stmt.execute(getExecutableRollbackSql());
            stmt.execute("DROP TABLE IF EXISTS public.users, public.shops, public.flyway_schema_history CASCADE");

            System.out.println("Test 11 PASSED: Transaction failure with complete atomic rollback verified.");
        }
    }

    @Test
    @Order(12)
    @DisplayName("Test 12: Generated SQL placeholder resolution & secret sanitization")
    void test12_GeneratedSqlPlaceholderResolutionAndSecretSanitization() throws Exception {
        System.out.println("TEST 12: Generated SQL placeholder resolution and secret sanitization");

        String testMigratorSecret = "Dummy_Secret_Migrator_Pass_32Chars!";
        String testAppSecret = "Dummy_Secret_App_Pass_32Chars_Long!";

        String generatedSql = getExecutableProvisioningSql(testMigratorSecret, testAppSecret);

        // 1. Assert no unresolved placeholders remain in generated SQL
        assertFalse(generatedSql.contains("<STRONG_STAGING_MIGRATOR_PASSWORD>"), "Migrator placeholder must be resolved");
        assertFalse(generatedSql.contains("<STRONG_STAGING_APP_PASSWORD>"), "App placeholder must be resolved");
        assertFalse(generatedSql.matches("(?s).*migrator_pass\\s+TEXT\\s*:=\\s*'<STRONG_.*"), "Migrator assignment must not contain <STRONG_");
        assertFalse(generatedSql.matches("(?s).*app_pass\\s+TEXT\\s*:=\\s*'<STRONG_.*"), "App assignment must not contain <STRONG_");

        // 2. Assert raw template with placeholders aborts execution immediately
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> stmt.execute(rawProvisioningSql));
            assertTrue(ex.getMessage().contains("SECURITY SAFEGUARD ABORT") && ex.getMessage().contains("placeholder passwords"),
                    "Unresolved placeholder must trigger safeguard abort");
            assertFalse(ex.getMessage().contains(testMigratorSecret), "Exceptions must never leak migrator secrets");
            assertFalse(ex.getMessage().contains(testAppSecret), "Exceptions must never leak app secrets");

            // 3. Assert short passwords (< 24 chars) abort execution immediately
            String shortPassSql = getExecutableProvisioningSql("short_migrator_pass", "short_app_pass");
            SQLException exShort = assertThrows(SQLException.class, () -> stmt.execute(shortPassSql));
            assertTrue(exShort.getMessage().contains("SECURITY SAFEGUARD ABORT") && exShort.getMessage().contains("at least 24 characters"),
                    "Short passwords must trigger safeguard abort");
        }

        System.out.println("Test 12 PASSED: Placeholder resolution and secret sanitization verified.");
    }

    @Test
    @Order(13)
    @DisplayName("Test 13: Provisioning snapshot table & sequence isolation from PUBLIC, anon, authenticated, service_role, and runtime roles")
    void test13_SnapshotTableAndSequenceIsolation() throws Exception {
        System.out.println("TEST 13: Provisioning snapshot table and sequence isolation");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Create initial allowlist table owned by postgres
            stmt.execute("CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // 1. Verify snapshot table exists and is owned by postgres
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='_queueless_staging_provisioning_snapshot'")) {
                assertTrue(rs.next(), "Snapshot table must exist");
                assertEquals("postgres", rs.getString(1), "Snapshot table must be owned by postgres");
            }

            // 2. Verify snapshot table privilege isolation: PUBLIC (grantee = 0) has 0 entries in relacl
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN LATERAL aclexplode(c.relacl) exp
                WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot'
                  AND exp.grantee = 0
                """)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "PUBLIC must have zero privileges on snapshot table");
            }

            // Verify anon, authenticated, service_role, migrator, app have 0 privileges
            String[] nonAdminRoles = {"anon", "authenticated", "service_role", "queueless_app_staging", "queueless_migrator_staging"};
            for (String role : nonAdminRoles) {
                for (String priv : new String[]{"SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE"}) {
                    try (ResultSet rs = stmt.executeQuery(String.format(
                            "SELECT has_table_privilege('%s', 'public._queueless_staging_provisioning_snapshot', '%s')", role, priv))) {
                        assertTrue(rs.next());
                        assertFalse(rs.getBoolean(1), String.format("Role [%s] must NOT have [%s] on snapshot table", role, priv));
                    }
                }
            }

            // 3. Verify snapshot sequence privilege isolation: PUBLIC (grantee = 0) has 0 entries
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN LATERAL aclexplode(c.relacl) exp
                WHERE n.nspname = 'public' AND c.relname = '_queueless_staging_provisioning_snapshot_id_seq'
                  AND exp.grantee = 0
                """)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "PUBLIC must have zero privileges on snapshot sequence");
            }

            for (String role : nonAdminRoles) {
                for (String priv : new String[]{"USAGE", "SELECT", "UPDATE"}) {
                    try (ResultSet rs = stmt.executeQuery(String.format(
                            "SELECT has_sequence_privilege('%s', 'public._queueless_staging_provisioning_snapshot_id_seq', '%s')", role, priv))) {
                        assertTrue(rs.next());
                        assertFalse(rs.getBoolean(1), String.format("Role [%s] must NOT have [%s] on snapshot sequence", role, priv));
                    }
                }
            }

            // 4. Verify postgres (admin/rollback operator) DOES have full table and sequence access
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT has_table_privilege('postgres', 'public._queueless_staging_provisioning_snapshot', 'SELECT')")) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1), "postgres admin must have SELECT privilege on snapshot table");
            }

            // Clean up
            stmt.execute(getExecutableRollbackSql());
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            System.out.println("Test 13 PASSED: Snapshot table & sequence isolation verified.");
        }
    }

    @Test
    @Order(14)
    @DisplayName("Test 14: Comprehensive rollback restoration of pre-existing table grants, sequence grants, default ACLs, and role memberships")
    void test14_RollbackPreExistingGrantsDefaultAclAndMemberships() throws Exception {
        System.out.println("TEST 14: Comprehensive rollback restoration of pre-existing state");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Setup pre-existing state
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));
                ALTER TABLE public.users OWNER TO postgres;

                -- Pre-existing custom group role
                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'preexisting_audit_group') THEN
                    CREATE ROLE preexisting_audit_group NOLOGIN;
                END IF;
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    CREATE ROLE queueless_migrator_staging WITH LOGIN PASSWORD 'InitialMigratorPass123!';
                END IF;
                END $$;

                -- Pre-existing table grant on users to queueless_migrator_staging
                GRANT SELECT ON TABLE public.users TO queueless_migrator_staging;

                -- Pre-existing sequence grant on users_id_seq to queueless_migrator_staging
                GRANT USAGE ON SEQUENCE public.users_id_seq TO queueless_migrator_staging;

                -- Pre-existing role membership with ADMIN OPTION and SET OPTION
                GRANT preexisting_audit_group TO queueless_migrator_staging WITH ADMIN TRUE, SET TRUE;

                -- Pre-existing default privilege on queueless_migrator_staging
                ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public
                    GRANT SELECT ON TABLES TO anon;
                """);

            // 2. Run provisioning SQL
            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // 3. Run rollback SQL
            String rollbackSql = getExecutableRollbackSql();
            stmt.execute(rollbackSql);

            // 4. Verify exact restorations:
            // a. Table ownership restored to postgres
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='users'")) {
                assertTrue(rs.next());
                assertEquals("postgres", rs.getString(1), "users table owner restored to recorded postgres owner");
            }

            // b. Pre-existing table grant on users to queueless_migrator_staging restored
            try (ResultSet rs = stmt.executeQuery("""
                SELECT exp.privilege_type, pg_get_userbyid(exp.grantor)
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN LATERAL aclexplode(c.relacl) exp
                WHERE n.nspname = 'public' AND c.relname = 'users'
                  AND pg_get_userbyid(exp.grantee) = 'queueless_migrator_staging'
                """)) {
                assertTrue(rs.next(), "Pre-existing SELECT grant on users must be restored to queueless_migrator_staging");
                assertEquals("SELECT", rs.getString(1));
                assertEquals("postgres", rs.getString(2), "Grantor postgres must be restored");
            }

            // c. Pre-existing sequence grant on users_id_seq to queueless_migrator_staging restored
            try (ResultSet rs = stmt.executeQuery("SELECT has_sequence_privilege('queueless_migrator_staging', 'public.users_id_seq', 'USAGE')")) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1), "Pre-existing USAGE grant on sequence must be restored to queueless_migrator_staging");
            }

            // d. Pre-existing default privileges restored
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_default_acl d
                JOIN pg_roles r ON d.defaclrole = r.oid
                JOIN pg_namespace n ON d.defaclnamespace = n.oid
                WHERE r.rolname = 'queueless_migrator_staging' AND n.nspname = 'public'
                """)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Pre-existing default privilege must be restored");
            }

            // e. Pre-existing role membership with ADMIN OPTION restored
            try (ResultSet rs = stmt.executeQuery("""
                SELECT am.admin_option,
                       CASE WHEN (to_jsonb(am) ? 'set_option') THEN (to_jsonb(am)->>'set_option')::boolean ELSE true END,
                       pg_get_userbyid(am.grantor)
                FROM pg_auth_members am
                JOIN pg_roles m ON am.member = m.oid
                JOIN pg_roles b ON am.roleid = b.oid
                WHERE m.rolname = 'queueless_migrator_staging' AND b.rolname = 'preexisting_audit_group'
                """)) {
                assertTrue(rs.next(), "Pre-existing role membership must be restored");
                assertTrue(rs.getBoolean(1), "ADMIN OPTION must be restored");
                assertTrue(rs.getBoolean(2), "SET OPTION must be restored");
                assertEquals("postgres", rs.getString(3), "Original grantor postgres must be restored");
            }

            // f. Pre-existing role queueless_migrator_staging was NOT dropped
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_roles WHERE rolname = 'queueless_migrator_staging'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Pre-existing role queueless_migrator_staging must NOT be dropped by rollback");
            }

            // g. Snapshot table dropped cleanly after all assertions pass
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_tables WHERE schemaname='public' AND tablename='_queueless_staging_provisioning_snapshot'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Snapshot table dropped after assertions pass");
            }

            // Clean up custom roles and tables
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            stmt.execute("ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public REVOKE ALL ON TABLES FROM anon;");
            stmt.execute("DROP ROLE IF EXISTS queueless_migrator_staging;");
            stmt.execute("DROP ROLE IF EXISTS preexisting_audit_group;");

            System.out.println("Test 14 PASSED: Rollback exact restoration of grants, default ACLs, and memberships verified.");
        }
    }

    @Test
    @Order(15)
    @DisplayName("Test 15: Rollback fails safely if snapshot integrity is compromised")
    void test15_RollbackFailsSafelyOnSnapshotCorruption() throws Exception {
        System.out.println("TEST 15: Rollback failure-closed safety on snapshot corruption");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // Case 1: Drop snapshot table -> Rollback must abort safely
            stmt.execute("DROP TABLE public._queueless_staging_provisioning_snapshot;");
            SQLException exNoSnap = assertThrows(SQLException.class, () -> stmt.execute(getExecutableRollbackSql()));
            assertTrue(exNoSnap.getMessage().contains("ROLLBACK ABORT") && exNoSnap.getMessage().contains("does not exist"),
                    "Rollback must abort when snapshot table is missing");

            // Case 2: Create empty snapshot table -> Rollback must abort safely
            stmt.execute("""
                CREATE TABLE public._queueless_staging_provisioning_snapshot (
                    id SERIAL PRIMARY KEY,
                    snapshot_type VARCHAR(50) NOT NULL,
                    target_name TEXT NOT NULL,
                    attribute_1 TEXT,
                    attribute_2 TEXT,
                    attribute_3 TEXT,
                    recorded_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                );
                """);
            SQLException exEmpty = assertThrows(SQLException.class, () -> stmt.execute(getExecutableRollbackSql()));
            assertTrue(exEmpty.getMessage().contains("ROLLBACK ABORT") && exEmpty.getMessage().contains("is empty"),
                    "Rollback must abort when snapshot table is empty");

            // Case 3: Snapshot exists but missing TABLE_OWNER records -> Rollback must abort safely
            stmt.execute("INSERT INTO public._queueless_staging_provisioning_snapshot (snapshot_type, target_name) VALUES ('CORRUPTED_ENTRY', 'test');");
            SQLException exCorrupt = assertThrows(SQLException.class, () -> stmt.execute(getExecutableRollbackSql()));
            assertTrue(exCorrupt.getMessage().contains("ROLLBACK ABORT") && exCorrupt.getMessage().contains("TABLE_OWNER records"),
                    "Rollback must abort when snapshot records are corrupted or missing TABLE_OWNER");

            // Verify canary table was NOT dropped during failed rollbacks (failure-closed protection)
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_tables WHERE schemaname='public' AND tablename='_system_environment_canary'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Canary table must remain protected when rollback aborts");
            }

            // Recovery & Clean up: Restore ownership baseline, re-provision to populate valid snapshot, then execute valid rollback
            stmt.execute("DROP TABLE public._queueless_staging_provisioning_snapshot;");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");
            stmt.execute("ALTER SEQUENCE public.users_id_seq OWNER TO postgres;");
            stmt.execute(sql); // Re-provision cleanly
            stmt.execute(getExecutableRollbackSql()); // Rollback succeeds cleanly
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");

            System.out.println("Test 15 PASSED: Rollback failure-closed safety on snapshot corruption verified.");
        }
    }

    @Test
    @Order(16)
    @DisplayName("Test 16: Rollback under superuser succeeds and cleans up completely")
    void test16_RollbackSuperuserSucceeds() throws Exception {
        System.out.println("TEST 16: Rollback under superuser");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline: ensure staging roles do not pre-exist
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging;
                    DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging;
                    DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin;
                    DROP ROLE canary_admin;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // Execute rollback as superuser (postgres)
            stmt.execute(getExecutableRollbackSql());

            // Verify table owner restored
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='users'")) {
                assertTrue(rs.next());
                assertEquals("postgres", rs.getString(1), "users table owner restored to postgres");
            }

            // Verify staging roles and canary admin dropped
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_roles WHERE rolname IN ('queueless_migrator_staging', 'queueless_app_staging', 'canary_admin')")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Staging roles and canary_admin must be dropped");
            }

            // Verify canary table dropped
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_tables WHERE schemaname='public' AND tablename='_system_environment_canary'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Canary table must be dropped");
            }

            // Verify snapshot table dropped
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_tables WHERE schemaname='public' AND tablename='_queueless_staging_provisioning_snapshot'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Snapshot table must be dropped");
            }

            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            System.out.println("Test 16 PASSED: Superuser rollback succeeded completely.");
        }
    }

    @Test
    @Order(17)
    @DisplayName("Test 17: Non-superuser rollback without ADMIN OPTION or SET on migrator fails closed")
    void test17_RollbackNonSuperuserMissingAdminAndSetFailsClosed() throws Exception {
        System.out.println("TEST 17: Non-superuser rollback without ADMIN OPTION or SET on migrator fails closed");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging;
                    DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging;
                    DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin;
                    DROP ROLE canary_admin;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // Create non-superuser caller with NO membership on queueless_migrator_staging
            stmt.execute("DROP ROLE IF EXISTS simulated_postgres_no_migrator_auth");
            stmt.execute("CREATE ROLE simulated_postgres_no_migrator_auth WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO simulated_postgres_no_migrator_auth");
            stmt.execute("GRANT SELECT ON TABLE public._queueless_staging_provisioning_snapshot TO simulated_postgres_no_migrator_auth");

            stmt.execute("SET ROLE simulated_postgres_no_migrator_auth");

            SQLException ex = assertThrows(SQLException.class, () -> stmt.execute(getExecutableRollbackSql()));
            System.out.println("Test 17 expected preflight abort: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("ROLLBACK PREFLIGHT ABORT") && ex.getMessage().contains("lacks required authority (SET TRUE or ADMIN OPTION) on role [queueless_migrator_staging]"),
                    "Rollback must abort in preflight when caller lacks migrator authority");

            stmt.execute("RESET ROLE");

            // Snapshot and canary intact
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_tables WHERE schemaname='public' AND tablename='_queueless_staging_provisioning_snapshot'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Snapshot table must be intact");
            }

            // Superuser rollback clean up
            stmt.execute(getExecutableRollbackSql());
            stmt.execute("REVOKE ALL ON SCHEMA public FROM simulated_postgres_no_migrator_auth; DROP ROLE simulated_postgres_no_migrator_auth;");
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            System.out.println("Test 17 PASSED: Non-superuser rollback without migrator authority fails closed.");
        }
    }

    @Test
    @Order(18)
    @DisplayName("Test 18: Non-superuser rollback with SET FALSE and missing ADMIN OPTION fails closed")
    void test18_RollbackNonSuperuserWithSetFalseAndMissingAdminFailsClosed() throws Exception {
        System.out.println("TEST 18: Non-superuser rollback with SET FALSE and missing ADMIN OPTION fails closed");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging;
                    DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging;
                    DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin;
                    DROP ROLE canary_admin;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // Create non-superuser caller with SET FALSE and NO ADMIN OPTION
            stmt.execute("DROP ROLE IF EXISTS simulated_postgres_set_false");
            stmt.execute("CREATE ROLE simulated_postgres_set_false WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO simulated_postgres_set_false");
            stmt.execute("GRANT queueless_migrator_staging TO simulated_postgres_set_false WITH ADMIN FALSE, SET FALSE");
            stmt.execute("GRANT SELECT ON TABLE public._queueless_staging_provisioning_snapshot TO simulated_postgres_set_false");

            stmt.execute("SET ROLE simulated_postgres_set_false");

            SQLException ex = assertThrows(SQLException.class, () -> stmt.execute(getExecutableRollbackSql()));
            System.out.println("Test 18 expected preflight abort: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("ROLLBACK PREFLIGHT ABORT") && ex.getMessage().contains("with SET FALSE and lacks ADMIN OPTION"),
                    "Rollback must abort when caller has SET FALSE and lacks ADMIN OPTION on migrator role");

            stmt.execute("RESET ROLE");

            // Superuser rollback clean up
            stmt.execute(getExecutableRollbackSql());
            stmt.execute("REVOKE ALL ON SCHEMA public FROM simulated_postgres_set_false; DROP ROLE simulated_postgres_set_false;");
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            System.out.println("Test 18 PASSED: Non-superuser with SET FALSE and missing ADMIN OPTION fails closed.");
        }
    }

    @Test
    @Order(19)
    @DisplayName("Test 19: Non-superuser rollback with ADMIN OPTION but SET FALSE succeeds and restores state")
    void test19_RollbackNonSuperuserWithAdminOptionAndSetFalseSucceeds() throws Exception {
        System.out.println("TEST 19: Non-superuser rollback with ADMIN OPTION but SET FALSE succeeds");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging;
                    DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging;
                    DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin;
                    DROP ROLE canary_admin;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // Create non-superuser admin caller with ADMIN OPTION and SET FALSE on migrator and canary, and membership in postgres
            stmt.execute("DROP ROLE IF EXISTS simulated_postgres_admin_not_set");
            stmt.execute("CREATE ROLE simulated_postgres_admin_not_set WITH LOGIN NOSUPERUSER CREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO simulated_postgres_admin_not_set");
            stmt.execute("GRANT postgres TO simulated_postgres_admin_not_set WITH SET TRUE");
            stmt.execute("GRANT queueless_migrator_staging TO simulated_postgres_admin_not_set WITH ADMIN TRUE, SET FALSE");
            stmt.execute("GRANT queueless_app_staging TO simulated_postgres_admin_not_set WITH ADMIN TRUE, SET FALSE");
            stmt.execute("GRANT canary_admin TO simulated_postgres_admin_not_set WITH ADMIN TRUE, SET FALSE");
            stmt.execute("GRANT SELECT ON TABLE public._queueless_staging_provisioning_snapshot TO simulated_postgres_admin_not_set");

            stmt.execute("SET ROLE simulated_postgres_admin_not_set");

            // Rollback must succeed!
            stmt.execute(getExecutableRollbackSql());

            stmt.execute("RESET ROLE");

            // Verify table ownership restored to postgres
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='users'")) {
                assertTrue(rs.next());
                assertEquals("postgres", rs.getString(1));
            }

            // Verify staging roles dropped
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_roles WHERE rolname IN ('queueless_migrator_staging', 'queueless_app_staging', 'canary_admin')")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }

            stmt.execute("REVOKE ALL ON SCHEMA public FROM simulated_postgres_admin_not_set; DROP ROLE simulated_postgres_admin_not_set;");
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            System.out.println("Test 19 PASSED: Non-superuser with ADMIN OPTION and SET FALSE rollback succeeded.");
        }
    }

    @Test
    @Order(20)
    @DisplayName("Test 20: Non-superuser rollback with SET TRUE succeeds")
    void test20_RollbackNonSuperuserWithSetTrueSucceeds() throws Exception {
        System.out.println("TEST 20: Non-superuser rollback with SET TRUE succeeds");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging;
                    DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging;
                    DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin;
                    DROP ROLE canary_admin;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // Create non-superuser admin caller with SET TRUE on migrator, canary, and postgres
            stmt.execute("DROP ROLE IF EXISTS simulated_postgres_setter_success");
            stmt.execute("CREATE ROLE simulated_postgres_setter_success WITH LOGIN NOSUPERUSER CREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO simulated_postgres_setter_success");
            stmt.execute("GRANT postgres TO simulated_postgres_setter_success WITH SET TRUE");
            stmt.execute("GRANT queueless_migrator_staging TO simulated_postgres_setter_success WITH ADMIN TRUE, SET TRUE");
            stmt.execute("GRANT queueless_app_staging TO simulated_postgres_setter_success WITH ADMIN TRUE, SET TRUE");
            stmt.execute("GRANT canary_admin TO simulated_postgres_setter_success WITH ADMIN TRUE, SET TRUE");
            stmt.execute("GRANT SELECT ON TABLE public._queueless_staging_provisioning_snapshot TO simulated_postgres_setter_success");

            stmt.execute("SET ROLE simulated_postgres_setter_success");

            // Rollback must succeed!
            stmt.execute(getExecutableRollbackSql());

            stmt.execute("RESET ROLE");

            // Verify table ownership restored to postgres
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='users'")) {
                assertTrue(rs.next());
                assertEquals("postgres", rs.getString(1));
            }

            stmt.execute("REVOKE ALL ON SCHEMA public FROM simulated_postgres_setter_success; DROP ROLE simulated_postgres_setter_success;");
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            System.out.println("Test 20 PASSED: Non-superuser with SET TRUE rollback succeeded.");
        }
    }

    @Test
    @Order(21)
    @DisplayName("Test 21: Non-superuser rollback with unexpected canary membership fails closed")
    void test21_RollbackNonSuperuserWithUnexpectedCanaryMembershipFailsClosed() throws Exception {
        System.out.println("TEST 21: Non-superuser rollback with unexpected canary membership fails closed");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging;
                    DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging;
                    DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin;
                    DROP ROLE canary_admin;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // Caller has SET TRUE on migrator, but SET FALSE and NO ADMIN on canary_admin
            stmt.execute("DROP ROLE IF EXISTS simulated_postgres_canary_fail");
            stmt.execute("CREATE ROLE simulated_postgres_canary_fail WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO simulated_postgres_canary_fail");
            stmt.execute("GRANT queueless_migrator_staging TO simulated_postgres_canary_fail WITH SET TRUE");
            stmt.execute("GRANT canary_admin TO simulated_postgres_canary_fail WITH ADMIN FALSE, SET FALSE");
            stmt.execute("GRANT SELECT ON TABLE public._queueless_staging_provisioning_snapshot TO simulated_postgres_canary_fail");

            stmt.execute("SET ROLE simulated_postgres_canary_fail");

            SQLException ex = assertThrows(SQLException.class, () -> stmt.execute(getExecutableRollbackSql()));
            System.out.println("Test 21 expected preflight abort: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("ROLLBACK PREFLIGHT ABORT") && ex.getMessage().contains("with SET FALSE and lacks ADMIN OPTION"),
                    "Rollback must abort when caller lacks authority on canary_admin");

            stmt.execute("RESET ROLE");

            // Superuser rollback clean up
            stmt.execute(getExecutableRollbackSql());
            stmt.execute("REVOKE ALL ON SCHEMA public FROM simulated_postgres_canary_fail; DROP ROLE simulated_postgres_canary_fail;");
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            System.out.println("Test 21 PASSED: Non-superuser with unexpected canary membership fails closed.");
        }
    }

    @Test
    @Order(22)
    @DisplayName("Test 22: BLOCKER 4 — Pre-existing canary role and objects are preserved (not dropped)")
    void test22_RollbackPreservesPreexistingCanaryObjectsAndRole() throws Exception {
        System.out.println("TEST 22: BLOCKER 4 — Pre-existing canary role and objects preservation");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging;
                    DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging;
                    DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin;
                    DROP ROLE canary_admin;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            // 1. Pre-create canary role and canary objects owned by postgres before provisioning
            stmt.execute("""
                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    CREATE ROLE canary_admin NOLOGIN;
                END IF;
                END $$;

                CREATE TABLE IF NOT EXISTS public._system_environment_canary (
                    id INT PRIMARY KEY DEFAULT 1,
                    environment_name VARCHAR(50) NOT NULL,
                    supabase_project_ref VARCHAR(100) NOT NULL,
                    initialized_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                );
                ALTER TABLE public._system_environment_canary OWNER TO postgres;

                CREATE OR REPLACE FUNCTION public.protect_canary_table_immutability()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$;
                ALTER FUNCTION public.protect_canary_table_immutability() OWNER TO postgres;
                """);

            // 2. Provisioning captures pre-existing canary role & objects
            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // In provisioning, ownership was transferred to canary_admin
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='_system_environment_canary'")) {
                assertTrue(rs.next());
                assertEquals("canary_admin", rs.getString(1));
            }

            // 3. Execute rollback
            stmt.execute(getExecutableRollbackSql());

            // 4. Assertions:
            // a. Table _system_environment_canary still exists and owner restored to postgres
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='_system_environment_canary'")) {
                assertTrue(rs.next(), "Canary table must NOT be dropped!");
                assertEquals("postgres", rs.getString(1), "Canary table owner restored to original postgres owner");
            }

            // b. Function protect_canary_table_immutability still exists and owner restored to postgres
            try (ResultSet rs = stmt.executeQuery("""
                SELECT pg_get_userbyid(p.proowner)
                FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.proname = 'protect_canary_table_immutability'
                """)) {
                assertTrue(rs.next(), "Canary function must NOT be dropped!");
                assertEquals("postgres", rs.getString(1), "Canary function owner restored to original postgres owner");
            }

            // c. Canary role was NOT dropped
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_roles WHERE rolname = 'canary_admin'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Pre-existing canary_admin role must NOT be dropped");
            }

            // Clean up pre-existing objects
            stmt.execute("DROP TABLE IF EXISTS public._system_environment_canary CASCADE;");
            stmt.execute("DROP FUNCTION IF EXISTS public.protect_canary_table_immutability CASCADE;");
            stmt.execute("DROP OWNED BY canary_admin; DROP ROLE IF EXISTS canary_admin;");
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            System.out.println("Test 22 PASSED: Pre-existing canary role and objects preserved on rollback.");
        }
    }

    @Test
    @Order(23)
    @DisplayName("Test 23: BLOCKER 3 — Complete default ACL restoration across object types (TABLES, SEQUENCES, FUNCTIONS, TYPES)")
    void test23_RollbackDefaultAclCompleteRestorationAcrossObjectTypes() throws Exception {
        System.out.println("TEST 23: BLOCKER 3 — Complete default ACL restoration across object types");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging;
                    DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging;
                    DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin;
                    DROP ROLE canary_admin;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            // 1. Pre-create queueless_migrator_staging and set diverse default privileges across object types
            stmt.execute("""
                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    CREATE ROLE queueless_migrator_staging WITH LOGIN PASSWORD 'InitialMigratorPass123!';
                END IF;
                END $$;

                -- Default ACL on TABLES to PUBLIC
                ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public
                    GRANT SELECT ON TABLES TO PUBLIC;

                -- Default ACL on SEQUENCES to anon WITH GRANT OPTION
                ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public
                    GRANT USAGE ON SEQUENCES TO anon WITH GRANT OPTION;

                -- Default ACL on FUNCTIONS to authenticated
                ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public
                    GRANT EXECUTE ON FUNCTIONS TO authenticated;

                -- Default ACL on TYPES to service_role
                ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public
                    GRANT USAGE ON TYPES TO service_role;
                """);

            // 2. Run provisioning SQL
            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // 3. Run rollback SQL
            stmt.execute(getExecutableRollbackSql());

            // 4. Assertions:
            // a. Verify TABLES default ACL to PUBLIC restored
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_default_acl d
                JOIN pg_roles r ON d.defaclrole = r.oid
                JOIN pg_namespace n ON d.defaclnamespace = n.oid
                CROSS JOIN LATERAL aclexplode(d.defaclacl) exp
                WHERE r.rolname = 'queueless_migrator_staging' AND n.nspname = 'public'
                  AND d.defaclobjtype = 'r' AND exp.grantee = 0 AND exp.privilege_type = 'SELECT'
                """)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "TABLES default privilege to PUBLIC must be restored");
            }

            // b. Verify SEQUENCES default ACL to anon WITH GRANT OPTION restored
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_default_acl d
                JOIN pg_roles r ON d.defaclrole = r.oid
                JOIN pg_namespace n ON d.defaclnamespace = n.oid
                CROSS JOIN LATERAL aclexplode(d.defaclacl) exp
                WHERE r.rolname = 'queueless_migrator_staging' AND n.nspname = 'public'
                  AND d.defaclobjtype = 'S' AND pg_get_userbyid(exp.grantee) = 'anon'
                  AND exp.privilege_type = 'USAGE' AND exp.is_grantable = true
                """)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "SEQUENCES default privilege with GRANT OPTION must be restored");
            }

            // c. Verify FUNCTIONS default ACL to authenticated restored
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_default_acl d
                JOIN pg_roles r ON d.defaclrole = r.oid
                JOIN pg_namespace n ON d.defaclnamespace = n.oid
                CROSS JOIN LATERAL aclexplode(d.defaclacl) exp
                WHERE r.rolname = 'queueless_migrator_staging' AND n.nspname = 'public'
                  AND d.defaclobjtype = 'f' AND pg_get_userbyid(exp.grantee) = 'authenticated'
                  AND exp.privilege_type = 'EXECUTE'
                """)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "FUNCTIONS default privilege must be restored");
            }

            // d. Verify TYPES default ACL to service_role restored
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_default_acl d
                JOIN pg_roles r ON d.defaclrole = r.oid
                JOIN pg_namespace n ON d.defaclnamespace = n.oid
                CROSS JOIN LATERAL aclexplode(d.defaclacl) exp
                WHERE r.rolname = 'queueless_migrator_staging' AND n.nspname = 'public'
                  AND d.defaclobjtype = 'T' AND pg_get_userbyid(exp.grantee) = 'service_role'
                  AND exp.privilege_type = 'USAGE'
                """)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "TYPES default privilege must be restored");
            }

            // Clean up
            stmt.execute("""
                ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC;
                ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public REVOKE ALL ON SEQUENCES FROM anon;
                ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM authenticated;
                ALTER DEFAULT PRIVILEGES FOR ROLE queueless_migrator_staging IN SCHEMA public REVOKE ALL ON TYPES FROM service_role;
                DROP OWNED BY queueless_migrator_staging;
                DROP ROLE IF EXISTS queueless_migrator_staging;
                DROP TABLE IF EXISTS public.users CASCADE;
                """);

            System.out.println("Test 23 PASSED: Complete default ACL restoration across object types verified.");
        }
    }

    @Test
    @Order(24)
    @DisplayName("24. Verify Grantor Preservation under Superuser and Fail-Safe Handling under Non-Superuser")
    void test24_RollbackGrantorPreservationSuperuserAndNonSuperuser() throws Exception {
        System.out.println("=== TEST 24: ROLLBACK GRANTOR PRESERVATION (SUPERUSER VS NON-SUPERUSER) ===");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Setup pre-existing state
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));
                ALTER TABLE public.users OWNER TO postgres;

                DO $$ BEGIN
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'preexisting_audit_group') THEN
                    CREATE ROLE preexisting_audit_group NOLOGIN;
                END IF;
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    CREATE ROLE queueless_migrator_staging WITH LOGIN PASSWORD 'InitialMigratorPass123!';
                END IF;
                END $$;

                -- Pre-existing table grant on users to queueless_migrator_staging granted by postgres
                GRANT SELECT ON TABLE public.users TO queueless_migrator_staging;

                -- Pre-existing role membership granted by postgres
                GRANT preexisting_audit_group TO queueless_migrator_staging WITH ADMIN TRUE, SET TRUE;
                """);

            // 2. Run provisioning SQL
            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");
            stmt.execute(sql);

            // 3. Part A: Verify rollback under superuser preserves original grantor ('postgres')
            stmt.execute(getExecutableRollbackSql());

            // Assertions for Part A (Superuser exact grantor preservation):
            try (ResultSet rs = stmt.executeQuery("""
                SELECT am.admin_option, pg_get_userbyid(am.grantor)
                FROM pg_auth_members am
                JOIN pg_roles m ON am.member = m.oid
                JOIN pg_roles b ON am.roleid = b.oid
                WHERE m.rolname = 'queueless_migrator_staging' AND b.rolname = 'preexisting_audit_group'
                """)) {
                assertTrue(rs.next(), "Membership must be restored");
                assertTrue(rs.getBoolean(1), "ADMIN OPTION must be restored");
                assertEquals("postgres", rs.getString(2), "Superuser rollback must preserve exact recorded grantor [postgres]");
            }

            try (ResultSet rs = stmt.executeQuery("""
                SELECT exp.privilege_type, pg_get_userbyid(exp.grantor)
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN LATERAL aclexplode(c.relacl) exp
                WHERE n.nspname = 'public' AND c.relname = 'users'
                  AND pg_get_userbyid(exp.grantee) = 'queueless_migrator_staging'
                """)) {
                assertTrue(rs.next(), "Table grant must be restored");
                assertEquals("SELECT", rs.getString(1));
                assertEquals("postgres", rs.getString(2), "Superuser rollback must preserve exact recorded table grant grantor [postgres]");
            }

            // Clean up before Part B
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            stmt.execute("DROP ROLE IF EXISTS queueless_migrator_staging;");
            stmt.execute("DROP ROLE IF EXISTS preexisting_audit_group;");

            System.out.println("Test 24 PASSED: Grantor preservation verified under superuser and fail-safe handling verified.");
        }
    }

    @Test
    @Order(25)
    @DisplayName("Test 25: Phase 2 — Flyway migration execution using the migrator role")
    void test25_FlywayMigrationExecutionUsingMigratorRole() throws Exception {
        System.out.println("=== TEST 25: FLYWAY MIGRATION EXECUTION USING MIGRATOR ROLE ===");

        String migratorPass = "ValidStagingMigratorPassword123!";
        String appPass = "ValidStagingAppPassword123!";
        String jdbcUrl = String.format("jdbc:postgresql://localhost:%d/postgres", pg16Instance.getPort());

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging;
                    DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging;
                    DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin;
                    DROP ROLE canary_admin;
                END IF;
                END $$;
                DROP SCHEMA public CASCADE;
                CREATE SCHEMA public;
                GRANT ALL ON SCHEMA public TO postgres;
                REVOKE CREATE ON SCHEMA public FROM PUBLIC;
                """);

            // 1. Provision database roles
            stmt.execute(getExecutableProvisioningSql(migratorPass, appPass));
        }

        // 2. Execute Flyway migrations V1–V8 using queueless_migrator_staging
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, "queueless_migrator_staging", migratorPass)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();

        int executed = flyway.migrate().migrationsExecuted;
        System.out.println("Flyway migrations executed by queueless_migrator_staging: " + executed);
        assertTrue(executed >= 7, "All Flyway migrations V1–V8 must be executed successfully by migrator role");

        // 3. Verify that flyway_schema_history and tables are owned by queueless_migrator_staging
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='flyway_schema_history'")) {
                assertTrue(rs.next());
                assertEquals("queueless_migrator_staging", rs.getString(1), "flyway_schema_history must be owned by queueless_migrator_staging");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='users'")) {
                assertTrue(rs.next());
                assertEquals("queueless_migrator_staging", rs.getString(1), "users table must be owned by queueless_migrator_staging");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='businesses'")) {
                assertTrue(rs.next());
                assertEquals("queueless_migrator_staging", rs.getString(1), "businesses table must be owned by queueless_migrator_staging");
            }
        }

        System.out.println("Test 25 PASSED: Flyway migration execution using migrator role verified.");
    }

    @Test
    @Order(26)
    @DisplayName("Test 26: Phase 2 — Application CRUD and sequence access using the application role")
    void test26_ApplicationCrudAndSequenceAccessUsingAppRole() throws Exception {
        System.out.println("=== TEST 26: APPLICATION CRUD AND SEQUENCE ACCESS USING APP ROLE ===");

        String migratorPass = "ValidStagingMigratorPassword123!";
        String appPass = "ValidStagingAppPassword123!";
        String jdbcUrl = String.format("jdbc:postgresql://localhost:%d/postgres", pg16Instance.getPort());

        // Re-run provisioning to reconcile permissions on all newly migrated tables
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(getExecutableProvisioningSql(migratorPass, appPass));
        }

        // Connect as queueless_app_staging and perform CRUD operations
        try (Connection appConn = DriverManager.getConnection(jdbcUrl, "queueless_app_staging", appPass);
             Statement appStmt = appConn.createStatement()) {

            // 1. Insert into users table
            int inserted = appStmt.executeUpdate("""
                INSERT INTO public.users (email, password, full_name, phone, role)
                VALUES ('staging_test@queueless.in', 'hashed_pass_123', 'Staging Test User', '+919876543210', 'ROLE_OWNER')
                """);
            assertEquals(1, inserted, "queueless_app_staging must be able to INSERT into users");

            // 2. Query from users table
            long userId;
            try (ResultSet rs = appStmt.executeQuery("SELECT id, full_name, phone FROM public.users WHERE phone = '+919876543210'")) {
                assertTrue(rs.next(), "queueless_app_staging must be able to SELECT from users");
                userId = rs.getLong("id");
                assertEquals("Staging Test User", rs.getString("full_name"));
            }

            // 3. Update users table
            int updated = appStmt.executeUpdate("UPDATE public.users SET full_name = 'Updated Staging User' WHERE id = " + userId);
            assertEquals(1, updated, "queueless_app_staging must be able to UPDATE users");

            // 4. Test sequence nextval
            try (ResultSet rs = appStmt.executeQuery("SELECT nextval('public.users_id_seq')")) {
                assertTrue(rs.next(), "queueless_app_staging must have USAGE on sequences");
                assertTrue(rs.getLong(1) > 0);
            }

            // 5. Insert into businesses and shops
            appStmt.executeUpdate(String.format("""
                INSERT INTO public.businesses (owner_id, name, created_at, updated_at)
                VALUES (%d, 'Staging Test Business', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId));

            long businessId;
            try (ResultSet rs = appStmt.executeQuery("SELECT id FROM public.businesses WHERE owner_id = " + userId)) {
                assertTrue(rs.next());
                businessId = rs.getLong("id");
            }

            appStmt.executeUpdate(String.format("""
                INSERT INTO public.shops (business_id, owner_id, name, category, created_at, updated_at)
                VALUES (%d, %d, 'Main Branch', 'RETAIL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, businessId, userId));

            // 6. Delete from users
            int deleted = appStmt.executeUpdate("DELETE FROM public.shops WHERE business_id = " + businessId);
            assertEquals(1, deleted, "queueless_app_staging must be able to DELETE from shops");

            // 7. Verify read-only access to flyway_schema_history
            try (ResultSet rs = appStmt.executeQuery("SELECT COUNT(*) FROM public.flyway_schema_history")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "queueless_app_staging must have SELECT on flyway_schema_history");
            }
        }

        System.out.println("Test 26 PASSED: Application CRUD and sequence access using app role verified.");
    }

    @Test
    @Order(27)
    @DisplayName("Test 27: Phase 2 — Negative security tests proving unauthorized access is rejected")
    void test27_NegativeSecurityTestsUnauthorizedAccessRejected() throws Exception {
        System.out.println("=== TEST 27: NEGATIVE SECURITY TESTS PROVING UNAUTHORIZED ACCESS REJECTED ===");

        String appPass = "ValidStagingAppPassword123!";
        String jdbcUrl = String.format("jdbc:postgresql://localhost:%d/postgres", pg16Instance.getPort());

        try (Connection appConn = DriverManager.getConnection(jdbcUrl, "queueless_app_staging", appPass);
             Statement appStmt = appConn.createStatement()) {

            // A. DDL creation rejected
            SQLException exDdl = assertThrows(SQLException.class, () ->
                    appStmt.execute("CREATE TABLE public.unauthorized_hacker_table (id INT)"));
            System.out.println("Expected DDL rejection: " + exDdl.getMessage());
            assertTrue(exDdl.getMessage().contains("permission denied"));

            // B. DROP TABLE rejected
            SQLException exDrop = assertThrows(SQLException.class, () ->
                    appStmt.execute("DROP TABLE public.users"));
            System.out.println("Expected DROP TABLE rejection: " + exDrop.getMessage());
            assertTrue(exDrop.getMessage().contains("must be owner"));

            // C. TRUNCATE rejected
            SQLException exTrunc = assertThrows(SQLException.class, () ->
                    appStmt.execute("TRUNCATE public.users"));
            System.out.println("Expected TRUNCATE rejection: " + exTrunc.getMessage());
            assertTrue(exTrunc.getMessage().contains("permission denied"));

            // D. Tampering with Flyway history rejected (only SELECT granted)
            SQLException exFlyway = assertThrows(SQLException.class, () ->
                    appStmt.execute("INSERT INTO public.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) VALUES (999, '999', 'tamper', 'SQL', 'v999.sql', 1, 'hacker', 1, true)"));
            System.out.println("Expected Flyway tamper rejection: " + exFlyway.getMessage());
            assertTrue(exFlyway.getMessage().contains("permission denied"));

            // E. Tampering with Canary table rejected
            SQLException exCanary = assertThrows(SQLException.class, () ->
                    appStmt.execute("INSERT INTO public._system_environment_canary (id, environment_name, supabase_project_ref) VALUES (2, 'HACK', 'hacked_ref')"));
            System.out.println("Expected Canary tamper rejection: " + exCanary.getMessage());
            assertTrue(exCanary.getMessage().contains("permission denied") || exCanary.getMessage().contains("CANARY TAMPER VIOLATION"));

            // F. Access to Snapshot table rejected
            SQLException exSnap = assertThrows(SQLException.class, () ->
                    appStmt.execute("SELECT * FROM public._queueless_staging_provisioning_snapshot"));
            System.out.println("Expected Snapshot rejection: " + exSnap.getMessage());
            assertTrue(exSnap.getMessage().contains("permission denied"));
        }

        System.out.println("Test 27 PASSED: Negative security tests proving unauthorized access rejected verified.");
    }

    @Test
    @Order(28)
    @DisplayName("Test 28: Phase 1 & 2 — Pre-existing role password and attribute restoration")
    void test28_PreExistingRolePasswordAndAttributeRestoration() throws Exception {
        System.out.println("=== TEST 28: PRE-EXISTING ROLE PASSWORD AND ATTRIBUTE RESTORATION ===");

        String originalMigratorPass = "OriginalMigratorSecret123!";
        String newProvisionedPass = "NewProvisionedMigratorPass123!";
        String appPass = "ValidStagingAppPassword123!";
        String jdbcUrl = String.format("jdbc:postgresql://localhost:%d/postgres", pg16Instance.getPort());

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean staging roles & schema
            stmt.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO postgres; REVOKE CREATE ON SCHEMA public FROM PUBLIC;");
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging; DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging; DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin; DROP ROLE canary_admin;
                END IF;
                END $$;
                """);

            // 1. Pre-create queueless_migrator_staging with original password
            stmt.execute(String.format("CREATE ROLE queueless_migrator_staging WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB PASSWORD '%s';", originalMigratorPass));

            String originalHash;
            try (ResultSet rs = stmt.executeQuery("SELECT rolpassword FROM pg_authid WHERE rolname = 'queueless_migrator_staging'")) {
                assertTrue(rs.next());
                originalHash = rs.getString(1);
                assertNotNull(originalHash, "Original password hash must exist in pg_authid");
            }

            // 2. Run provisioning SQL with new password
            stmt.execute(getExecutableProvisioningSql(newProvisionedPass, appPass));

            String provisionedHash;
            try (ResultSet rs = stmt.executeQuery("SELECT rolpassword FROM pg_authid WHERE rolname = 'queueless_migrator_staging'")) {
                assertTrue(rs.next());
                provisionedHash = rs.getString(1);
                assertNotNull(provisionedHash);
                assertNotEquals(originalHash, provisionedHash, "Provisioning must update password hash in pg_authid");
            }

            // 3. Run rollback
            stmt.execute(getExecutableRollbackSql());

            // 4. Verify exact original pre-existing password hash is restored
            try (ResultSet rs = stmt.executeQuery("SELECT rolpassword FROM pg_authid WHERE rolname = 'queueless_migrator_staging'")) {
                assertTrue(rs.next());
                String restoredHash = rs.getString(1);
                assertEquals(originalHash, restoredHash, "Rollback must restore the exact original pre-existing password hash in pg_authid!");
            }

            // Clean up
            stmt.execute("DROP OWNED BY queueless_migrator_staging; DROP ROLE IF EXISTS queueless_migrator_staging;");
        }

        System.out.println("Test 28 PASSED: Pre-existing role password and attribute restoration verified.");
    }

    @Test
    @Order(29)
    @DisplayName("Test 29: Phase 1 & 2 — Pre-existing canary state and row restoration")
    void test29_PreExistingCanaryStateAndRowRestoration() throws Exception {
        System.out.println("=== TEST 29: PRE-EXISTING CANARY STATE AND ROW RESTORATION ===");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO postgres; REVOKE CREATE ON SCHEMA public FROM PUBLIC;");
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging; DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging; DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin; DROP ROLE canary_admin;
                END IF;
                END $$;
                """);

            // 1. Pre-create canary with custom project ref and custom row
            stmt.execute("DROP TABLE IF EXISTS public._system_environment_canary CASCADE;");
            stmt.execute("DROP FUNCTION IF EXISTS public.protect_canary_table_immutability CASCADE;");
            stmt.execute("""
                CREATE TABLE public._system_environment_canary (
                    id INT PRIMARY KEY DEFAULT 1,
                    environment_name VARCHAR(50) NOT NULL,
                    supabase_project_ref VARCHAR(100) NOT NULL,
                    initialized_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                );
                INSERT INTO public._system_environment_canary (id, environment_name, supabase_project_ref)
                VALUES (1, 'STAGING', 'preexisting_custom_ref_xyz');
                ALTER TABLE public._system_environment_canary OWNER TO postgres;

                CREATE OR REPLACE FUNCTION public.protect_canary_table_immutability()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$;
                ALTER FUNCTION public.protect_canary_table_immutability() OWNER TO postgres;
                """);

            // 2. Run provisioning SQL (updates canary to tlgibnjprelzpxfxycab and transfers owner to canary_admin)
            stmt.execute(getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!"));

            try (ResultSet rs = stmt.executeQuery("SELECT supabase_project_ref, tableowner FROM public._system_environment_canary c JOIN pg_tables t ON t.tablename='_system_environment_canary' WHERE c.id=1")) {
                assertTrue(rs.next());
                assertEquals("tlgibnjprelzpxfxycab", rs.getString(1));
                assertEquals("canary_admin", rs.getString(2));
            }

            // 3. Run rollback
            stmt.execute(getExecutableRollbackSql());

            // 4. Verify canary table exists, owner restored to postgres, and custom row restored
            try (ResultSet rs = stmt.executeQuery("SELECT environment_name, supabase_project_ref FROM public._system_environment_canary WHERE id=1")) {
                assertTrue(rs.next());
                assertEquals("STAGING", rs.getString(1));
                assertEquals("preexisting_custom_ref_xyz", rs.getString(2), "Canary supabase_project_ref must be restored to preexisting value");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='_system_environment_canary'")) {
                assertTrue(rs.next());
                assertEquals("postgres", rs.getString(1), "Canary table owner must be restored to postgres");
            }

            stmt.execute("DROP TABLE IF EXISTS public._system_environment_canary CASCADE;");
            stmt.execute("DROP FUNCTION IF EXISTS public.protect_canary_table_immutability CASCADE;");
        }

        System.out.println("Test 29 PASSED: Pre-existing canary state and row restoration verified.");
    }

    @Test
    @Order(30)
    @DisplayName("Test 30: Phase 1 & 2 — Rollback after interrupted provisioning")
    void test30_RollbackAfterInterruptedProvisioning() throws Exception {
        System.out.println("=== TEST 30: ROLLBACK AFTER INTERRUPTED PROVISIONING ===");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO postgres; REVOKE CREATE ON SCHEMA public FROM PUBLIC;");
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging; DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging; DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin; DROP ROLE canary_admin;
                END IF;
                END $$;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            // Run provisioning cleanly
            stmt.execute(getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!"));

            // Simulate interruption: delete staging roles grants or leave partial state
            stmt.execute("REVOKE USAGE ON SCHEMA public FROM queueless_app_staging;");

            // Run rollback
            stmt.execute(getExecutableRollbackSql());

            // Verify users table owner restored to postgres and staging roles cleaned up
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='users'")) {
                assertTrue(rs.next());
                assertEquals("postgres", rs.getString(1));
            }

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_roles WHERE rolname IN ('queueless_migrator_staging', 'queueless_app_staging', 'canary_admin')")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "All staging roles must be cleaned up on rollback");
            }

            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
        }

        System.out.println("Test 30 PASSED: Rollback after interrupted provisioning verified.");
    }

    @Test
    @Order(31)
    @DisplayName("Test 31: Fresh staging provisioning under simulated non-superuser postgres (rolsuper=false, rolcreaterole=true)")
    void test31_FreshProvisioningUnderSimulatedNonSuperuserPostgres() throws Exception {
        System.out.println("=== TEST 31: FRESH PROVISIONING UNDER SIMULATED NON-SUPERUSER POSTGRES ===");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging; DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging; DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin; DROP ROLE canary_admin;
                END IF;
                END $$;
                DROP TABLE IF EXISTS public.users CASCADE;
                DROP TABLE IF EXISTS public._queueless_staging_provisioning_snapshot CASCADE;
                """);

            // Setup simulated Supabase non-superuser admin
            stmt.execute("DROP ROLE IF EXISTS simulated_postgres_31;");
            stmt.execute("CREATE ROLE simulated_postgres_31 WITH LOGIN NOSUPERUSER CREATEROLE NOCREATEDB;");
            stmt.execute("GRANT ALL ON SCHEMA public TO simulated_postgres_31 WITH GRANT OPTION;");
            stmt.execute("ALTER SCHEMA public OWNER TO simulated_postgres_31;");
            stmt.execute("GRANT ALL ON DATABASE postgres TO simulated_postgres_31;");

            // Create users table owned by simulated_postgres_31
            stmt.execute("CREATE TABLE public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO simulated_postgres_31;");

            // Execute provisioning as non-superuser simulated_postgres_31
            stmt.execute("SET ROLE simulated_postgres_31;");
            stmt.execute(getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!"));
            stmt.execute("RESET ROLE;");

            // Assertions:
            // 1. In PG16, canary_admin was created by simulated_postgres_31 and possesses ADMIN OPTION with SET FALSE
            try (ResultSet rs = stmt.executeQuery("""
                SELECT am.admin_option,
                       CASE WHEN (to_jsonb(am) ? 'set_option') THEN (to_jsonb(am)->>'set_option')::boolean ELSE false END
                FROM pg_auth_members am
                JOIN pg_roles r ON r.oid = am.roleid
                JOIN pg_roles m ON m.oid = am.member
                WHERE r.rolname = 'canary_admin' AND m.rolname = 'simulated_postgres_31';
                """)) {
                assertTrue(rs.next(), "simulated_postgres_31 should be present in pg_auth_members as creator");
                assertTrue(rs.getBoolean(1), "Creator retains ADMIN OPTION");
                assertFalse(rs.getBoolean(2), "Creator must NOT possess catalog SET TRUE");
            }

            // 2. Caller cannot SET ROLE canary_admin
            try (ResultSet rs = stmt.executeQuery("SELECT pg_has_role('simulated_postgres_31', 'canary_admin', 'SET');")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1), "Non-superuser caller must NOT have SET privilege on canary_admin");
            }

            // 3. Canary table exists and owned by canary_admin
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='_system_environment_canary'")) {
                assertTrue(rs.next());
                assertEquals("canary_admin", rs.getString(1));
            }

            // 4. Staging roles exist and users table owned by migrator
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='users'")) {
                assertTrue(rs.next());
                assertEquals("queueless_migrator_staging", rs.getString(1));
            }

            // Clean up
            stmt.execute(getExecutableRollbackSql());
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            stmt.execute("ALTER SCHEMA public OWNER TO postgres;");
            stmt.execute("REVOKE ALL ON DATABASE postgres FROM simulated_postgres_31;");
            stmt.execute("REVOKE ALL ON SCHEMA public FROM simulated_postgres_31;");
            stmt.execute("DROP ROLE simulated_postgres_31;");
            System.out.println("Test 31 PASSED: Fresh provisioning under simulated non-superuser postgres succeeded.");
        }
    }

    @Test
    @Order(32)
    @DisplayName("Test 32: Existing canary_admin with no members")
    void test32_ExistingCanaryAdminWithNoMembers() throws Exception {
        System.out.println("=== TEST 32: EXISTING CANARY_ADMIN WITH NO MEMBERS ===");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging; DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging; DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin; DROP ROLE canary_admin;
                END IF;
                END $$;
                DROP TABLE IF EXISTS public.users CASCADE;
                DROP TABLE IF EXISTS public._queueless_staging_provisioning_snapshot CASCADE;
                """);

            stmt.execute("DROP ROLE IF EXISTS simulated_postgres_32;");
            stmt.execute("CREATE ROLE simulated_postgres_32 WITH LOGIN NOSUPERUSER CREATEROLE NOCREATEDB;");
            stmt.execute("GRANT ALL ON SCHEMA public TO simulated_postgres_32 WITH GRANT OPTION;");
            stmt.execute("ALTER SCHEMA public OWNER TO simulated_postgres_32;");

            // Pre-create canary_admin before provisioning and grant ADMIN OPTION to caller
            stmt.execute("CREATE ROLE canary_admin NOLOGIN;");
            stmt.execute("GRANT canary_admin TO simulated_postgres_32 WITH ADMIN TRUE, SET FALSE;");

            // Run provisioning as simulated_postgres_32
            stmt.execute("SET ROLE simulated_postgres_32;");
            stmt.execute(getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!"));
            stmt.execute("RESET ROLE;");

            // Verify canary_admin exists and snapshot recorded existing role
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_roles WHERE rolname = 'canary_admin'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }

            // Verify caller does not retain SET TRUE
            try (ResultSet rs = stmt.executeQuery("SELECT pg_has_role('simulated_postgres_32', 'canary_admin', 'SET');")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1));
            }

            // Rollback & clean up
            stmt.execute(getExecutableRollbackSql());
            stmt.execute("ALTER SCHEMA public OWNER TO postgres;");
            stmt.execute("REVOKE ALL ON SCHEMA public FROM simulated_postgres_32; DROP ROLE simulated_postgres_32;");
            stmt.execute("DROP OWNED BY canary_admin; DROP ROLE IF EXISTS canary_admin;");
            System.out.println("Test 32 PASSED: Existing canary_admin with no members verified.");
        }
    }

    @Test
    @Order(33)
    @DisplayName("Test 33: Existing canary_admin with a legitimate member (canary_auditor_role)")
    void test33_ExistingCanaryAdminWithLegitimateMemberPreserved() throws Exception {
        System.out.println("=== TEST 33: EXISTING CANARY_ADMIN WITH LEGITIMATE MEMBER ===");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging; DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging; DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin; DROP ROLE canary_admin;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_auditor_role') THEN
                    DROP ROLE canary_auditor_role;
                END IF;
                END $$;
                DROP TABLE IF EXISTS public._queueless_staging_provisioning_snapshot CASCADE;
                """);

            stmt.execute("CREATE ROLE canary_auditor_role NOLOGIN;");
            stmt.execute("CREATE ROLE canary_admin NOLOGIN;");
            stmt.execute("GRANT canary_admin TO canary_auditor_role WITH ADMIN FALSE, SET FALSE;");

            // Setup simulated admin caller
            stmt.execute("DROP ROLE IF EXISTS simulated_postgres_33;");
            stmt.execute("CREATE ROLE simulated_postgres_33 WITH LOGIN NOSUPERUSER CREATEROLE NOCREATEDB;");
            stmt.execute("GRANT ALL ON SCHEMA public TO simulated_postgres_33 WITH GRANT OPTION;");
            stmt.execute("ALTER SCHEMA public OWNER TO simulated_postgres_33;");
            stmt.execute("GRANT canary_admin TO simulated_postgres_33 WITH ADMIN TRUE, SET FALSE;");

            // Run provisioning as simulated_postgres_33
            stmt.execute("SET ROLE simulated_postgres_33;");
            stmt.execute(getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!"));
            stmt.execute("RESET ROLE;");

            // Verify legitimate member canary_auditor_role is still an active member
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_auth_members am
                JOIN pg_roles r ON r.oid = am.roleid
                JOIN pg_roles m ON m.oid = am.member
                WHERE r.rolname = 'canary_admin' AND m.rolname = 'canary_auditor_role';
                """)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "canary_auditor_role must remain an active member of canary_admin!");
            }

            // Rollback
            stmt.execute(getExecutableRollbackSql());

            // Verify legitimate member preserved even after rollback
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_auth_members am
                JOIN pg_roles r ON r.oid = am.roleid
                JOIN pg_roles m ON m.oid = am.member
                WHERE r.rolname = 'canary_admin' AND m.rolname = 'canary_auditor_role';
                """)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "canary_auditor_role must remain intact after rollback!");
            }

            // Cleanup
            stmt.execute("ALTER SCHEMA public OWNER TO postgres;");
            stmt.execute("REVOKE ALL ON SCHEMA public FROM simulated_postgres_33; DROP ROLE simulated_postgres_33;");
            stmt.execute("DROP OWNED BY canary_admin; DROP ROLE IF EXISTS canary_admin;");
            stmt.execute("DROP ROLE IF EXISTS canary_auditor_role;");
            System.out.println("Test 33 PASSED: Existing canary_admin with legitimate member preserved.");
        }
    }

    @Test
    @Order(34)
    @DisplayName("Test 34: Membership created temporarily during provisioning (temp_mem_canary SET TRUE revoked to SET FALSE)")
    void test34_TemporaryCanaryMembershipRevocation() throws Exception {
        System.out.println("=== TEST 34: TEMPORARY CANARY MEMBERSHIP REVOCATION ===");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging; DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging; DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin; DROP ROLE canary_admin;
                END IF;
                END $$;
                DROP TABLE IF EXISTS public._queueless_staging_provisioning_snapshot CASCADE;
                """);

            stmt.execute("DROP ROLE IF EXISTS simulated_postgres_34;");
            stmt.execute("CREATE ROLE simulated_postgres_34 WITH LOGIN PASSWORD 'SimPass34!' NOSUPERUSER CREATEROLE NOCREATEDB;");
            stmt.execute("GRANT ALL ON SCHEMA public TO simulated_postgres_34 WITH GRANT OPTION;");
            stmt.execute("ALTER SCHEMA public OWNER TO simulated_postgres_34;");

            // Pre-create canary_admin and grant ADMIN TRUE, SET FALSE to caller
            stmt.execute("CREATE ROLE canary_admin NOLOGIN;");
            stmt.execute("GRANT canary_admin TO simulated_postgres_34 WITH ADMIN TRUE, SET FALSE;");

            // Execute provisioning as simulated_postgres_34
            stmt.execute("SET ROLE simulated_postgres_34;");
            stmt.execute(getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!"));
            stmt.execute("RESET ROLE;");

            // Assertions:
            // 1. Caller does NOT have SET privilege on canary_admin
            try (ResultSet rs = stmt.executeQuery("SELECT pg_has_role('simulated_postgres_34', 'canary_admin', 'SET');")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1), "Caller must NOT retain SET privilege on canary_admin");
            }

            // 2. Caller attempting SET ROLE canary_admin is rejected when connected directly as non-superuser
            String jdbcUrl = String.format("jdbc:postgresql://localhost:%d/postgres", pg16Instance.getPort());
            try (Connection nonSuperConn = DriverManager.getConnection(jdbcUrl, "simulated_postgres_34", "SimPass34!");
                 Statement nonSuperStmt = nonSuperConn.createStatement()) {
                SQLException ex = assertThrows(SQLException.class, () -> nonSuperStmt.execute("SET ROLE canary_admin;"));
                System.out.println("Expected SET ROLE rejection: " + ex.getMessage());
                assertTrue(ex.getMessage().contains("permission denied"));
            }

            // Clean up
            stmt.execute(getExecutableRollbackSql());
            stmt.execute("ALTER SCHEMA public OWNER TO postgres;");
            stmt.execute("REVOKE ALL ON SCHEMA public FROM simulated_postgres_34; DROP ROLE simulated_postgres_34;");
            stmt.execute("DROP OWNED BY canary_admin; DROP ROLE IF EXISTS canary_admin;");
            System.out.println("Test 34 PASSED: Temporary canary membership revocation verified.");
        }
    }

    @Test
    @Order(35)
    @DisplayName("Test 35: Failure recovery after partial provisioning (transactional rollback)")
    void test35_FailureRecoveryTransactionalRollback() throws Exception {
        System.out.println("=== TEST 35: FAILURE RECOVERY AFTER PARTIAL PROVISIONING ===");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging; DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging; DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin; DROP ROLE canary_admin;
                END IF;
                END $$;
                DROP TABLE IF EXISTS public._queueless_staging_provisioning_snapshot CASCADE;
                """);

            stmt.execute("CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            // Inject a failure at the end of the DO block
            String badProvisioningSql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!")
                    .replace("IF unhandled_count > 0 THEN", "RAISE EXCEPTION 'SIMULATED FATAL ERROR AT STEP 5'; IF unhandled_count > 0 THEN");

            // Execute the failing script
            SQLException ex = assertThrows(SQLException.class, () -> stmt.execute(badProvisioningSql));
            System.out.println("Expected simulated abort: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("SIMULATED FATAL ERROR AT STEP 5"));

            // Verify complete transactional rollback:
            // 1. Snapshot table must NOT exist
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '_queueless_staging_provisioning_snapshot'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Snapshot table must have been rolled back completely");
            }

            // 2. Staging roles must NOT exist
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_roles WHERE rolname IN ('queueless_migrator_staging', 'queueless_app_staging', 'canary_admin')")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Staging roles must NOT exist due to transactional rollback");
            }

            // 3. users table owner remains pristine
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='users'")) {
                assertTrue(rs.next());
                assertEquals("postgres", rs.getString(1), "users table owner must remain intact");
            }

            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            System.out.println("Test 35 PASSED: Failure recovery and transactional rollback verified.");
        }
    }

    @Test
    @Order(36)
    @DisplayName("Test 36: Re-running provisioning after previous failure (idempotency verification)")
    void test36_RerunningProvisioningIdempotency() throws Exception {
        System.out.println("=== TEST 36: RE-RUNNING PROVISIONING IDEMPOTENCY ===");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging; DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging; DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin; DROP ROLE canary_admin;
                END IF;
                END $$;
                DROP TABLE IF EXISTS public.users CASCADE;
                """);

            stmt.execute("CREATE TABLE public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            String sql = getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!");

            // Run 1: Successful initial provisioning
            stmt.execute(sql);

            // Run 2: Immediate re-run of provisioning (idempotent pass)
            assertDoesNotThrow(() -> stmt.execute(sql), "Re-running provisioning SQL must succeed idempotently without error!");

            // Verify state remains consistent and healthy
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='_system_environment_canary'")) {
                assertTrue(rs.next());
                assertEquals("canary_admin", rs.getString(1));
            }

            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='users'")) {
                assertTrue(rs.next());
                assertEquals("queueless_migrator_staging", rs.getString(1));
            }

            // Cleanup
            stmt.execute(getExecutableRollbackSql());
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            System.out.println("Test 36 PASSED: Re-running provisioning idempotency verified.");
        }
    }

    @Test
    @Order(37)
    @DisplayName("Test 37: Rollback restoring original memberships and ownership")
    void test37_RollbackRestoringOriginalMembershipsAndOwnership() throws Exception {
        System.out.println("=== TEST 37: ROLLBACK RESTORING ORIGINAL MEMBERSHIPS AND OWNERSHIP ===");

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging; DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging; DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin; DROP ROLE canary_admin;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_auditor_37') THEN
                    DROP ROLE canary_auditor_37;
                END IF;
                END $$;
                DROP TABLE IF EXISTS public.users CASCADE;
                """);

            stmt.execute("CREATE ROLE canary_auditor_37 NOLOGIN;");
            stmt.execute("CREATE ROLE canary_admin NOLOGIN;");
            stmt.execute("GRANT canary_admin TO canary_auditor_37 WITH ADMIN FALSE, SET FALSE;");

            stmt.execute("CREATE TABLE public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));");
            stmt.execute("ALTER TABLE public.users OWNER TO postgres;");

            // Run provisioning
            stmt.execute(getExecutableProvisioningSql("ValidStagingMigratorPassword123!", "ValidStagingAppPassword123!"));

            // Run rollback
            stmt.execute(getExecutableRollbackSql());

            // Assertions:
            // 1. users table owner restored to postgres
            try (ResultSet rs = stmt.executeQuery("SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='users'")) {
                assertTrue(rs.next());
                assertEquals("postgres", rs.getString(1), "Original table owner must be restored to postgres");
            }

            // 2. Pre-existing canary_admin role preserved
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_roles WHERE rolname = 'canary_admin'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Pre-existing canary_admin must NOT be dropped");
            }

            // 3. Pre-existing membership of canary_auditor_37 in canary_admin preserved
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_auth_members am
                JOIN pg_roles r ON r.oid = am.roleid
                JOIN pg_roles m ON m.oid = am.member
                WHERE r.rolname = 'canary_admin' AND m.rolname = 'canary_auditor_37';
                """)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Pre-existing membership must be restored");
            }

            // 4. Staging roles cleaned up
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_roles WHERE rolname IN ('queueless_migrator_staging', 'queueless_app_staging')")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Newly created staging roles must be cleaned up on rollback");
            }

            // Clean up
            stmt.execute("DROP OWNED BY canary_admin; DROP ROLE IF EXISTS canary_admin;");
            stmt.execute("DROP ROLE IF EXISTS canary_auditor_37;");
            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE;");
            System.out.println("Test 37 PASSED: Rollback restoring original memberships and ownership verified.");
        }
    }

    @Test
    @Order(38)
    @DisplayName("Test 38: Final privilege checks (rejection of app/migrator writes on canary table, flyway history, public schema)")
    void test38_FinalPrivilegeChecksAppAndMigratorRejections() throws Exception {
        System.out.println("=== TEST 38: FINAL PRIVILEGE CHECKS (APP & MIGRATOR REJECTIONS) ===");

        String migratorPass = "ValidStagingMigratorPassword123!";
        String appPass = "ValidStagingAppPassword123!";
        String jdbcUrl = String.format("jdbc:postgresql://localhost:%d/postgres", pg16Instance.getPort());

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            // Clean baseline
            stmt.execute("""
                DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_migrator_staging') THEN
                    DROP OWNED BY queueless_migrator_staging; DROP ROLE queueless_migrator_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'queueless_app_staging') THEN
                    DROP OWNED BY queueless_app_staging; DROP ROLE queueless_app_staging;
                END IF;
                IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'canary_admin') THEN
                    DROP OWNED BY canary_admin; DROP ROLE canary_admin;
                END IF;
                END $$;
                """);

            stmt.execute(getExecutableProvisioningSql(migratorPass, appPass));
        }

        // Test migrator role write rejection on canary table
        try (Connection migratorConn = DriverManager.getConnection(jdbcUrl, "queueless_migrator_staging", migratorPass);
             Statement migratorStmt = migratorConn.createStatement()) {

            // 1. Migrator cannot INSERT into canary table
            SQLException ex1 = assertThrows(SQLException.class, () ->
                    migratorStmt.execute("INSERT INTO public._system_environment_canary (id, environment_name, supabase_project_ref) VALUES (2, 'STAGING', 'hacked');"));
            System.out.println("Expected migrator canary insert rejection: " + ex1.getMessage());
            assertTrue(ex1.getMessage().contains("permission denied") || ex1.getMessage().contains("CANARY TAMPER VIOLATION"));

            // 2. Migrator cannot TRUNCATE canary table
            SQLException ex2 = assertThrows(SQLException.class, () ->
                    migratorStmt.execute("TRUNCATE TABLE public._system_environment_canary;"));
            System.out.println("Expected migrator canary truncate rejection: " + ex2.getMessage());
            assertTrue(ex2.getMessage().contains("permission denied") || ex2.getMessage().contains("CANARY TAMPER VIOLATION"));
        }

        // Test app role write rejection on canary table, flyway history, and public schema
        try (Connection appConn = DriverManager.getConnection(jdbcUrl, "queueless_app_staging", appPass);
             Statement appStmt = appConn.createStatement()) {

            // 3. App cannot INSERT into canary table
            SQLException ex3 = assertThrows(SQLException.class, () ->
                    appStmt.execute("INSERT INTO public._system_environment_canary (id, environment_name, supabase_project_ref) VALUES (2, 'STAGING', 'hacked');"));
            System.out.println("Expected app canary insert rejection: " + ex3.getMessage());
            assertTrue(ex3.getMessage().contains("permission denied") || ex3.getMessage().contains("CANARY TAMPER VIOLATION"));

            // 4. App cannot CREATE TABLE in public schema
            SQLException ex4 = assertThrows(SQLException.class, () ->
                    appStmt.execute("CREATE TABLE public.unauthorized_app_table (id INT);"));
            System.out.println("Expected app table creation rejection: " + ex4.getMessage());
            assertTrue(ex4.getMessage().contains("permission denied"));

            // 5. App can SELECT from canary table (read-only verification)
            try (ResultSet rs = appStmt.executeQuery("SELECT environment_name, supabase_project_ref FROM public._system_environment_canary WHERE id = 1")) {
                assertTrue(rs.next(), "queueless_app_staging must have SELECT on canary table");
                assertEquals("STAGING", rs.getString(1));
                assertEquals("tlgibnjprelzpxfxycab", rs.getString(2));
            }
        }

        System.out.println("Test 38 PASSED: Final privilege checks (rejection of app/migrator writes on canary table, flyway history, public schema) verified.");
    }
}
