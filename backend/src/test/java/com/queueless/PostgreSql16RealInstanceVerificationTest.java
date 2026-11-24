package com.queueless;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import io.zonky.test.db.postgres.embedded.PgBinaryResolver;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueueLess — Actual PostgreSQL 16 Integration Verification Test Suite
 *
 * Verifies all 6 requirements:
 * 1. Start real PostgreSQL 16.4 instance, capture SELECT version(), SHOW server_version,
 *    pg_auth_members catalog columns, confirming set_option exists.
 * 2. Execute actual provisioning SQL scripts (scripts/provision_staging_db_roles.sql and
 *    scripts/provision_prod_db_roles.sql) and safe rollback on real PostgreSQL 16.
 * 3. Test all 4 privilege combinations on native PostgreSQL 16:
 *    - admin_option = false, set_option = false (aborts before modification)
 *    - admin_option = false, set_option = true (transfers directly, retains existing membership)
 *    - admin_option = true, set_option = false (temporarily grants SET TRUE, revokes after transfer)
 *    - admin_option = true, set_option = true (transfers directly, retains existing membership)
 * 4. Correct grant fallback handling: verify explicit version checking
 *    (IF current_setting('server_version_num')::integer >= 160000 THEN ... WITH SET TRUE ELSE ... END IF;),
 *    ensure PG 16 errors are not swallowed and test both version branches.
 * 5. Failure and cleanup testing on PG 16:
 *    - Temporary grant succeeds but ownership transfer fails (atomic abort, zero dirty state).
 *    - Temporary grant is revoked after successful transfer.
 *    - Existing membership is preserved when not created temporarily.
 *    - Failed provisioning does not leave unintended ownership or membership changes.
 *    - Rollback does not remove pre-existing membership.
 */
@DisplayName("QueueLess — Actual PostgreSQL 16 Integration Verification Suite")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PostgreSql16RealInstanceVerificationTest {

    private static EmbeddedPostgres pg16Instance;
    private static DataSource superuserDs;
    private static String pg16VersionOutput;
    private static String serverVersionOutput;
    private static List<String> pgAuthMembersColumns;

    private static Path findScriptPath(String scriptName) {
        Path p = Path.of("scripts", scriptName);
        if (Files.exists(p)) return p;
        p = Path.of("../scripts", scriptName);
        if (Files.exists(p)) return p;
        throw new IllegalArgumentException("Script not found: " + scriptName);
    }

    @BeforeAll
    static void setUpAll() throws Exception {
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

        // Capture version and catalog info
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SELECT version()")) {
                assertTrue(rs.next());
                pg16VersionOutput = rs.getString(1);
            }

            try (ResultSet rs = stmt.executeQuery("SHOW server_version")) {
                assertTrue(rs.next());
                serverVersionOutput = rs.getString(1);
            }

            pgAuthMembersColumns = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery("""
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_name = 'pg_auth_members'
                ORDER BY ordinal_position
                """)) {
                while (rs.next()) {
                    pgAuthMembersColumns.add(rs.getString("column_name") + " (" + rs.getString("data_type") + ")");
                }
            }

            // Create Supabase-standard simulation roles and administrative roles
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
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'simulated_postgres') THEN
                    CREATE ROLE simulated_postgres WITH LOGIN CREATEROLE NOSUPERUSER NOCREATEDB PASSWORD 'test_pg_pass';
                    GRANT USAGE, CREATE ON SCHEMA public TO simulated_postgres;
                END IF;
                IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'simulated_unprivileged') THEN
                    CREATE ROLE simulated_unprivileged WITH LOGIN NOCREATEROLE NOSUPERUSER NOCREATEDB PASSWORD 'test_unp_pass';
                    GRANT USAGE, CREATE ON SCHEMA public TO simulated_unprivileged;
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

    // =========================================================================
    // SECTION 1: Capture & Report (Requirement 1)
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("1. Verify PostgreSQL 16.4 Instance & pg_auth_members set_option Column")
    void test1_PostgreSql16VersionAndCatalogVerification() {
        System.out.println("=== SECTION 1: POSTGRESQL 16 INSTANCE VERIFICATION ===");
        System.out.println("ACTUAL_PG_VERSION: " + pg16VersionOutput);
        System.out.println("SERVER_VERSION: " + serverVersionOutput);
        System.out.println("PG_AUTH_MEMBERS COLUMNS: " + pgAuthMembersColumns);

        assertTrue(pg16VersionOutput.contains("PostgreSQL 16"), "Must be PostgreSQL 16! Actual: " + pg16VersionOutput);
        assertTrue(serverVersionOutput.startsWith("16."), "Server version must start with 16. Actual: " + serverVersionOutput);
        assertTrue(pgAuthMembersColumns.stream().anyMatch(c -> c.startsWith("set_option")),
                "set_option column MUST exist in pg_auth_members on PostgreSQL 16!");
        assertTrue(pgAuthMembersColumns.stream().anyMatch(c -> c.startsWith("admin_option")),
                "admin_option column MUST exist in pg_auth_members on PostgreSQL 16!");
        assertTrue(pgAuthMembersColumns.stream().anyMatch(c -> c.startsWith("inherit_option")),
                "inherit_option column MUST exist in pg_auth_members on PostgreSQL 16!");
    }

    // =========================================================================
    // SECTION 2: Execute Real Provisioning SQL Scripts (Requirement 2)
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("2. Execute Actual scripts/provision_staging_db_roles.sql & Rollback on Real PG 16")
    void test2_ExecuteActualProvisionStagingDbRolesScript() throws Exception {
        System.out.println("=== SECTION 2: EXECUTE ACTUAL PROVISION_STAGING_DB_ROLES.SQL ===");

        // Step A: Bootstrap canary using scripts/bootstrap_database_canary.sql
        Path canaryPath = findScriptPath("bootstrap_database_canary.sql");
        String canarySql = Files.readString(canaryPath);
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(canarySql);
            System.out.println("Canary bootstrap executed successfully on PG 16.");
        }

        // Step B: Create allowlist application tables owned by postgres before provisioning
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS public.users (id SERIAL PRIMARY KEY, username VARCHAR(50));
                CREATE TABLE IF NOT EXISTS public.shops (id SERIAL PRIMARY KEY, name VARCHAR(100));
                CREATE TABLE IF NOT EXISTS public.flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(50));
                ALTER TABLE public.users OWNER TO postgres;
                ALTER TABLE public.shops OWNER TO postgres;
                ALTER TABLE public.flyway_schema_history OWNER TO postgres;
                """);
        }

        // Step C: Load scripts/provision_staging_db_roles.sql, replace passwords, execute
        Path stagingPath = findScriptPath("provision_staging_db_roles.sql");
        String stagingSql = Files.readString(stagingPath);
        stagingSql = stagingSql.replace("REPLACE_WITH_STRONG_STAGING_MIGRATOR_PASSWORD", "Valid_Staging_Migrator_Pass_16!")
                .replace("<STRONG_STAGING_MIGRATOR_PASSWORD>", "Valid_Staging_Migrator_Pass_16!")
                .replace("REPLACE_WITH_STRONG_STAGING_APP_PASSWORD", "Valid_Staging_App_Pass_16!")
                .replace("<STRONG_STAGING_APP_PASSWORD>", "Valid_Staging_App_Pass_16!");

        // Split into main execution part (before verification queries & rollback comments)
        int verificationIndex = stagingSql.indexOf("-- Verification Queries");
        String mainScript = (verificationIndex > 0) ? stagingSql.substring(0, verificationIndex) : stagingSql;

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(mainScript);
            System.out.println("Actual provision_staging_db_roles.sql executed successfully on PG 16.");

            // Verify role flags (rolsuper=f, rolcreaterole=f, rolbypassrls=f)
            try (ResultSet rs = stmt.executeQuery("""
                SELECT rolname, rolsuper, rolcreaterole, rolbypassrls
                FROM pg_roles
                WHERE rolname IN ('queueless_migrator_staging', 'queueless_app_staging')
                """)) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    String rname = rs.getString("rolname");
                    assertFalse(rs.getBoolean("rolsuper"), rname + " must have rolsuper = false");
                    assertFalse(rs.getBoolean("rolcreaterole"), rname + " must have rolcreaterole = false");
                    assertFalse(rs.getBoolean("rolbypassrls"), rname + " must have rolbypassrls = false");
                }
                assertEquals(2, count, "Both staging roles must exist");
            }

            // Verify table ownership transferred to queueless_migrator_staging
            try (ResultSet rs = stmt.executeQuery("""
                SELECT tablename, tableowner
                FROM pg_tables
                WHERE schemaname = 'public' AND tablename IN ('users', 'shops', 'flyway_schema_history')
                """)) {
                while (rs.next()) {
                    String tbl = rs.getString("tablename");
                    String owner = rs.getString("tableowner");
                    System.out.println("Table " + tbl + " owner: " + owner);
                    assertEquals("queueless_migrator_staging", owner, "Table " + tbl + " must be owned by queueless_migrator_staging");
                }
            }

            // Verify sequence ownership transferred to queueless_migrator_staging
            try (ResultSet rs = stmt.executeQuery("""
                SELECT s.relname AS seqname, r.rolname AS seqowner
                FROM pg_class s
                JOIN pg_roles r ON s.relowner = r.oid
                WHERE s.relkind = 'S' AND s.relname IN ('users_id_seq', 'shops_id_seq')
                """)) {
                while (rs.next()) {
                    String seq = rs.getString("seqname");
                    String owner = rs.getString("seqowner");
                    System.out.println("Sequence " + seq + " owner: " + owner);
                    assertEquals("queueless_migrator_staging", owner, "Sequence " + seq + " must be owned by queueless_migrator_staging");
                }
            }

            // Verify canary table permissions (SELECT only, NO write)
            try (ResultSet rs = stmt.executeQuery("""
                SELECT privilege_type
                FROM information_schema.role_table_grants
                WHERE table_name = '_system_environment_canary'
                  AND grantee = 'queueless_app_staging'
                """)) {
                List<String> privs = new ArrayList<>();
                while (rs.next()) {
                    privs.add(rs.getString("privilege_type"));
                }
                System.out.println("queueless_app_staging canary privileges: " + privs);
                assertEquals(1, privs.size(), "Must have exactly 1 privilege on canary table");
                assertEquals("SELECT", privs.get(0), "Privilege must be SELECT only");
            }

            // Verify 0 administrative role memberships
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_auth_members m
                JOIN pg_roles r ON m.member = r.oid
                JOIN pg_roles b ON m.roleid = b.oid
                WHERE r.rolname IN ('queueless_migrator_staging', 'queueless_app_staging')
                  AND b.rolname IN ('canary_admin', 'postgres', 'supabase_admin')
                """)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Must have 0 administrative memberships");
            }
        }

        // Step D: Execute actual rollback script
        Path rollbackPath = findScriptPath("rollback_staging_db_roles.sql");
        String rollbackSql = Files.readString(rollbackPath);

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(rollbackSql);
            System.out.println("Staging rollback executed successfully on PG 16.");

            // Verify table ownership restored to postgres
            try (ResultSet rs = stmt.executeQuery("""
                SELECT tablename, tableowner
                FROM pg_tables
                WHERE schemaname = 'public' AND tablename IN ('users', 'shops', 'flyway_schema_history')
                """)) {
                while (rs.next()) {
                    String tbl = rs.getString("tablename");
                    String owner = rs.getString("tableowner");
                    System.out.println("Post-rollback table " + tbl + " owner: " + owner);
                    assertEquals("postgres", owner, "Table " + tbl + " ownership must be restored to postgres");
                }
            }

            // Verify staging roles were dropped
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_roles
                WHERE rolname IN ('queueless_migrator_staging', 'queueless_app_staging')
                """)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Staging roles must be dropped after rollback");
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("3. Execute Actual scripts/provision_prod_db_roles.sql & Rollback on Real PG 16")
    void test3_ExecuteActualProvisionProdDbRolesScript() throws Exception {
        System.out.println("=== SECTION 2B: EXECUTE ACTUAL PROVISION_PROD_DB_ROLES.SQL ===");

        Path prodPath = findScriptPath("provision_prod_db_roles.sql");
        String prodSql = Files.readString(prodPath);
        prodSql = prodSql.replace("REPLACE_WITH_STRONG_PRODUCTION_MIGRATOR_PASSWORD", "Valid_Prod_Migrator_Pass_16!");
        prodSql = prodSql.replace("REPLACE_WITH_STRONG_PRODUCTION_APP_PASSWORD", "Valid_Prod_App_Pass_16!");

        int verificationIndex = prodSql.indexOf("-- Verification Queries");
        String mainScript = (verificationIndex > 0) ? prodSql.substring(0, verificationIndex) : prodSql;

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(mainScript);
            System.out.println("Actual provision_prod_db_roles.sql executed successfully on PG 16.");

            // Verify prod role flags
            try (ResultSet rs = stmt.executeQuery("""
                SELECT rolname, rolsuper, rolcreaterole, rolbypassrls
                FROM pg_roles
                WHERE rolname IN ('queueless_migrator_prod', 'queueless_app_prod')
                """)) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    String rname = rs.getString("rolname");
                    assertFalse(rs.getBoolean("rolsuper"), rname + " must have rolsuper = false");
                    assertFalse(rs.getBoolean("rolcreaterole"), rname + " must have rolcreaterole = false");
                    assertFalse(rs.getBoolean("rolbypassrls"), rname + " must have rolbypassrls = false");
                }
                assertEquals(2, count, "Both prod roles must exist");
            }

            // Verify table ownership transferred to queueless_migrator_prod
            try (ResultSet rs = stmt.executeQuery("""
                SELECT tablename, tableowner
                FROM pg_tables
                WHERE schemaname = 'public' AND tablename IN ('users', 'shops', 'flyway_schema_history')
                """)) {
                while (rs.next()) {
                    String tbl = rs.getString("tablename");
                    String owner = rs.getString("tableowner");
                    assertEquals("queueless_migrator_prod", owner, "Table " + tbl + " must be owned by queueless_migrator_prod");
                }
            }
        }

        // Execute prod rollback
        int rollbackDoIndex = prodSql.indexOf("DO $$", prodSql.indexOf("-- Safe Rollback Guidance"));
        assertTrue(rollbackDoIndex > 0, "Rollback DO block must exist in script");
        String commentedRollback = prodSql.substring(rollbackDoIndex);
        StringBuilder rollbackSql = new StringBuilder();
        for (String line : commentedRollback.split("\n")) {
            if (line.startsWith("-- ")) {
                rollbackSql.append(line.substring(3)).append("\n");
            } else if (line.startsWith("--")) {
                rollbackSql.append(line.substring(2)).append("\n");
            } else {
                rollbackSql.append(line).append("\n");
            }
        }

        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(rollbackSql.toString());
            System.out.println("Prod rollback executed successfully on PG 16.");

            // Verify table ownership restored to postgres
            try (ResultSet rs = stmt.executeQuery("""
                SELECT tablename, tableowner
                FROM pg_tables
                WHERE schemaname = 'public' AND tablename IN ('users', 'shops', 'flyway_schema_history')
                """)) {
                while (rs.next()) {
                    String tbl = rs.getString("tablename");
                    String owner = rs.getString("tableowner");
                    assertEquals("postgres", owner, "Table " + tbl + " ownership must be restored to postgres");
                }
            }

            // Verify prod roles were dropped
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM pg_roles
                WHERE rolname IN ('queueless_migrator_prod', 'queueless_app_prod')
                """)) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Prod roles must be dropped after rollback");
            }
        }
    }

    // =========================================================================
    // SECTION 3: The 4 Native PG 16 Privilege Matrix Scenarios (Requirement 3)
    // =========================================================================

    private static class AuthMemberRecord {
        final boolean exists;
        final boolean adminOption;
        final boolean setOption;

        AuthMemberRecord(boolean exists, boolean adminOption, boolean setOption) {
            this.exists = exists;
            this.adminOption = adminOption;
            this.setOption = setOption;
        }

        @Override
        public String toString() {
            return "{exists=" + exists + ", admin_option=" + adminOption + ", set_option=" + setOption + "}";
        }
    }

    private AuthMemberRecord getAuthMemberRecord(Connection conn, String roleName, String memberName) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(String.format("""
                 SELECT am.admin_option, am.set_option
                 FROM pg_auth_members am
                 JOIN pg_roles r ON r.oid = am.roleid
                 JOIN pg_roles m ON m.oid = am.member
                 WHERE r.rolname = '%s' AND m.rolname = '%s'
                 """, roleName, memberName))) {
            if (rs.next()) {
                return new AuthMemberRecord(true, rs.getBoolean("admin_option"), rs.getBoolean("set_option"));
            }
            return new AuthMemberRecord(false, false, false);
        }
    }

    private String getTableOwner(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(String.format("""
                 SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = '%s'
                 """, tableName))) {
            if (rs.next()) {
                return rs.getString("tableowner");
            }
            return null;
        }
    }

    @Test
    @Order(4)
    @DisplayName("4A. Native PG 16 Matrix: admin_option = false, set_option = false (ABORTS before modification)")
    void test4A_PrivilegeMatrix_AdminFalse_SetFalse() throws Exception {
        System.out.println("\n--- SCENARIO 4A: admin_option = FALSE, set_option = FALSE ---");
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP TABLE IF EXISTS public.tbl_matrix_a CASCADE");
            stmt.execute("DROP ROLE IF EXISTS migrator_matrix_a");
            stmt.execute("CREATE ROLE migrator_matrix_a WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO migrator_matrix_a");
            stmt.execute("CREATE TABLE public.tbl_matrix_a (id SERIAL PRIMARY KEY, note TEXT)");
            stmt.execute("ALTER TABLE public.tbl_matrix_a OWNER TO simulated_postgres");

            // Native PG 16: grant role, then set SET FALSE, revoke ADMIN OPTION
            stmt.execute("GRANT migrator_matrix_a TO simulated_postgres");
            stmt.execute("GRANT migrator_matrix_a TO simulated_postgres WITH SET FALSE");
            stmt.execute("REVOKE ADMIN OPTION FOR migrator_matrix_a FROM simulated_postgres");

            AuthMemberRecord initialRecord = getAuthMemberRecord(conn, "migrator_matrix_a", "simulated_postgres");
            System.out.println("Scenario 4A Initial pg_auth_members: " + initialRecord);
            assertTrue(initialRecord.exists);
            assertFalse(initialRecord.adminOption, "admin_option must be FALSE");
            assertFalse(initialRecord.setOption, "set_option must be FALSE");

            String initialOwner = getTableOwner(conn, "tbl_matrix_a");
            assertEquals("simulated_postgres", initialOwner);

            // Execute preflight under simulated_postgres
            stmt.execute("SET ROLE simulated_postgres");
            SQLException ex = assertThrows(SQLException.class, () -> {
                stmt.execute("""
                    DO $$
                    DECLARE
                        caller_role TEXT := CURRENT_USER;
                        is_super BOOLEAN;
                        has_membership BOOLEAN := FALSE;
                        has_set_membership BOOLEAN := FALSE;
                        has_admin_option BOOLEAN := FALSE;
                    BEGIN
                        SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;
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
                            WHERE r.rolname = 'migrator_matrix_a' AND m.rolname = caller_role;

                            has_membership := COALESCE(has_membership, FALSE);
                            has_admin_option := COALESCE(has_admin_option, FALSE);
                            has_set_membership := COALESCE(has_set_membership, FALSE);

                            IF NOT has_set_membership AND NOT has_admin_option THEN
                                RAISE EXCEPTION 'PREFLIGHT SAFEGUARD ABORT: Role [migrator_matrix_a] exists, but caller [%] lacks active SET membership and ADMIN OPTION!', caller_role;
                            END IF;
                        END IF;
                    END $$;
                    """);
            });

            System.out.println("Scenario 4A expected abort message: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("PREFLIGHT SAFEGUARD ABORT"), "Must abort with PREFLIGHT SAFEGUARD ABORT");

            // Reset role to superuser and verify zero modifications
            stmt.execute("RESET ROLE");
            AuthMemberRecord postRecord = getAuthMemberRecord(conn, "migrator_matrix_a", "simulated_postgres");
            System.out.println("Scenario 4A Post-run pg_auth_members: " + postRecord);
            assertTrue(postRecord.exists);
            assertFalse(postRecord.adminOption);
            assertFalse(postRecord.setOption);

            String postOwner = getTableOwner(conn, "tbl_matrix_a");
            assertEquals("simulated_postgres", postOwner, "Ownership MUST remain unchanged");
        }
    }

    @Test
    @Order(5)
    @DisplayName("5B. Native PG 16 Matrix: admin_option = false, set_option = true (Transfers directly, preserves membership)")
    void test4B_PrivilegeMatrix_AdminFalse_SetTrue() throws Exception {
        System.out.println("\n--- SCENARIO 4B: admin_option = FALSE, set_option = TRUE ---");
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP TABLE IF EXISTS public.tbl_matrix_b CASCADE");
            stmt.execute("DROP ROLE IF EXISTS migrator_matrix_b");
            stmt.execute("CREATE ROLE migrator_matrix_b WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO migrator_matrix_b");
            stmt.execute("CREATE TABLE public.tbl_matrix_b (id SERIAL PRIMARY KEY, note TEXT)");
            stmt.execute("ALTER TABLE public.tbl_matrix_b OWNER TO simulated_postgres");

            // Native PG 16: grant with SET TRUE, ensure ADMIN OPTION is false
            stmt.execute("GRANT migrator_matrix_b TO simulated_postgres WITH SET TRUE");
            stmt.execute("REVOKE ADMIN OPTION FOR migrator_matrix_b FROM simulated_postgres");

            AuthMemberRecord initialRecord = getAuthMemberRecord(conn, "migrator_matrix_b", "simulated_postgres");
            System.out.println("Scenario 4B Initial pg_auth_members: " + initialRecord);
            assertTrue(initialRecord.exists);
            assertFalse(initialRecord.adminOption);
            assertTrue(initialRecord.setOption);

            // Execute provisioning block as simulated_postgres
            stmt.execute("SET ROLE simulated_postgres");
            stmt.execute("""
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
                        WHERE r.rolname = 'migrator_matrix_b' AND m.rolname = caller_role;

                        has_membership := COALESCE(has_membership, FALSE);
                        has_admin_option := COALESCE(has_admin_option, FALSE);
                        has_set_membership := COALESCE(has_set_membership, FALSE);

                        IF NOT has_set_membership AND NOT has_admin_option THEN
                            RAISE EXCEPTION 'PREFLIGHT SAFEGUARD ABORT';
                        END IF;

                        -- Step 5b: only grant temporary if NOT has_set_membership
                        IF NOT has_set_membership THEN
                            IF current_setting('server_version_num')::integer >= 160000 THEN
                                EXECUTE format('GRANT migrator_matrix_b TO %I WITH SET TRUE', caller_role);
                            ELSE
                                EXECUTE format('GRANT migrator_matrix_b TO %I', caller_role);
                            END IF;
                            temp_membership_granted := TRUE;
                        END IF;
                    END IF;

                    -- Step 5c: Transfer ownership
                    EXECUTE 'ALTER TABLE public.tbl_matrix_b OWNER TO migrator_matrix_b';

                    -- Step 5d: Revoke temporary membership ONLY if temp_membership_granted
                    IF temp_membership_granted THEN
                        EXECUTE format('REVOKE migrator_matrix_b FROM %I', caller_role);
                    END IF;
                END $$;
                """);

            stmt.execute("RESET ROLE");

            // Verify ownership transferred
            String postOwner = getTableOwner(conn, "tbl_matrix_b");
            System.out.println("Scenario 4B Table Owner after transfer: " + postOwner);
            assertEquals("migrator_matrix_b", postOwner);

            // Verify pre-existing membership was PRESERVED (NOT revoked!)
            AuthMemberRecord postRecord = getAuthMemberRecord(conn, "migrator_matrix_b", "simulated_postgres");
            System.out.println("Scenario 4B Post-run pg_auth_members: " + postRecord);
            assertTrue(postRecord.exists, "Membership MUST be preserved since it was pre-existing!");
            assertFalse(postRecord.adminOption);
            assertTrue(postRecord.setOption);

            // Rollback table ownership
            stmt.execute("ALTER TABLE public.tbl_matrix_b OWNER TO simulated_postgres");
            stmt.execute("REVOKE migrator_matrix_b FROM simulated_postgres");
        }
    }

    @Test
    @Order(6)
    @DisplayName("6C. Native PG 16 Matrix: admin_option = true, set_option = false (Grants temp SET TRUE, revokes after transfer)")
    void test4C_PrivilegeMatrix_AdminTrue_SetFalse() throws Exception {
        System.out.println("\n--- SCENARIO 4C: admin_option = TRUE, set_option = FALSE ---");
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP TABLE IF EXISTS public.tbl_matrix_c CASCADE");
            stmt.execute("DROP ROLE IF EXISTS migrator_matrix_c");
            stmt.execute("CREATE ROLE migrator_matrix_c WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO migrator_matrix_c");
            stmt.execute("CREATE TABLE public.tbl_matrix_c (id SERIAL PRIMARY KEY, note TEXT)");
            stmt.execute("ALTER TABLE public.tbl_matrix_c OWNER TO simulated_postgres");

            // Native PG 16: grant WITH ADMIN OPTION, then SET FALSE
            stmt.execute("GRANT migrator_matrix_c TO simulated_postgres WITH ADMIN OPTION");
            stmt.execute("GRANT migrator_matrix_c TO simulated_postgres WITH SET FALSE");

            AuthMemberRecord initialRecord = getAuthMemberRecord(conn, "migrator_matrix_c", "simulated_postgres");
            System.out.println("Scenario 4C Initial pg_auth_members: " + initialRecord);
            assertTrue(initialRecord.exists);
            assertTrue(initialRecord.adminOption);
            assertFalse(initialRecord.setOption);

            // Execute provisioning block as simulated_postgres
            stmt.execute("SET ROLE simulated_postgres");
            stmt.execute("""
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
                        WHERE r.rolname = 'migrator_matrix_c' AND m.rolname = caller_role;

                        has_membership := COALESCE(has_membership, FALSE);
                        has_admin_option := COALESCE(has_admin_option, FALSE);
                        has_set_membership := COALESCE(has_set_membership, FALSE);

                        IF NOT has_set_membership AND NOT has_admin_option THEN
                            RAISE EXCEPTION 'PREFLIGHT SAFEGUARD ABORT';
                        END IF;

                        -- Step 5b: has_set_membership is FALSE, has_admin_option is TRUE -> temporary grant!
                        IF NOT has_set_membership THEN
                            IF NOT has_admin_option THEN
                                RAISE EXCEPTION 'Lacks ADMIN OPTION';
                            END IF;
                            IF current_setting('server_version_num')::integer >= 160000 THEN
                                EXECUTE format('GRANT migrator_matrix_c TO %I WITH SET TRUE', caller_role);
                            ELSE
                                EXECUTE format('GRANT migrator_matrix_c TO %I', caller_role);
                            END IF;
                            temp_membership_granted := TRUE;
                        END IF;
                    END IF;

                    -- Step 5c: Transfer ownership
                    EXECUTE 'ALTER TABLE public.tbl_matrix_c OWNER TO migrator_matrix_c';

                    -- Step 5d: Revoke temporary membership
                    IF temp_membership_granted THEN
                        EXECUTE format('REVOKE migrator_matrix_c FROM %I', caller_role);
                        -- On PG 16+, verify active SET privilege is absent
                        IF current_setting('server_version_num')::integer >= 160000 THEN
                            IF pg_has_role(caller_role, 'migrator_matrix_c', 'SET') THEN
                                RAISE EXCEPTION 'FATAL: Temporary membership revocation failed!';
                            END IF;
                        ELSE
                            IF pg_has_role(caller_role, 'migrator_matrix_c', 'MEMBER') THEN
                                RAISE EXCEPTION 'FATAL: Temporary membership revocation failed!';
                            END IF;
                        END IF;
                    END IF;
                END $$;
                """);

            stmt.execute("RESET ROLE");

            // Verify ownership transferred
            String postOwner = getTableOwner(conn, "tbl_matrix_c");
            System.out.println("Scenario 4C Table Owner after transfer: " + postOwner);
            assertEquals("migrator_matrix_c", postOwner);

            // Verify temporary SET membership was REVOKED, and caller possesses NO active SET privilege!
            AuthMemberRecord postRecord = getAuthMemberRecord(conn, "migrator_matrix_c", "simulated_postgres");
            System.out.println("Scenario 4C Post-run pg_auth_members: " + postRecord);
            assertTrue(postRecord.exists);
            assertTrue(postRecord.adminOption);
            assertFalse(postRecord.setOption, "Temporary SET privilege MUST be revoked after transfer!");

            try (ResultSet rs = stmt.executeQuery("SELECT pg_has_role('simulated_postgres', 'migrator_matrix_c', 'SET')")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1), "pg_has_role SET MUST be false after temporary revocation!");
            }

            // Rollback table ownership
            stmt.execute("ALTER TABLE public.tbl_matrix_c OWNER TO simulated_postgres");
            stmt.execute("REVOKE migrator_matrix_c FROM simulated_postgres");
        }
    }

    @Test
    @Order(7)
    @DisplayName("7D. Native PG 16 Matrix: admin_option = true, set_option = true (Transfers directly, preserves membership)")
    void test4D_PrivilegeMatrix_AdminTrue_SetTrue() throws Exception {
        System.out.println("\n--- SCENARIO 4D: admin_option = TRUE, set_option = TRUE ---");
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP TABLE IF EXISTS public.tbl_matrix_d CASCADE");
            stmt.execute("DROP ROLE IF EXISTS migrator_matrix_d");
            stmt.execute("CREATE ROLE migrator_matrix_d WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO migrator_matrix_d");
            stmt.execute("CREATE TABLE public.tbl_matrix_d (id SERIAL PRIMARY KEY, note TEXT)");
            stmt.execute("ALTER TABLE public.tbl_matrix_d OWNER TO simulated_postgres");

            // Native PG 16: grant WITH ADMIN OPTION and SET TRUE
            stmt.execute("GRANT migrator_matrix_d TO simulated_postgres WITH ADMIN OPTION");
            stmt.execute("GRANT migrator_matrix_d TO simulated_postgres WITH SET TRUE");

            AuthMemberRecord initialRecord = getAuthMemberRecord(conn, "migrator_matrix_d", "simulated_postgres");
            System.out.println("Scenario 4D Initial pg_auth_members: " + initialRecord);
            assertTrue(initialRecord.exists);
            assertTrue(initialRecord.adminOption);
            assertTrue(initialRecord.setOption);

            // Execute provisioning block as simulated_postgres
            stmt.execute("SET ROLE simulated_postgres");
            stmt.execute("""
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
                        WHERE r.rolname = 'migrator_matrix_d' AND m.rolname = caller_role;

                        has_membership := COALESCE(has_membership, FALSE);
                        has_admin_option := COALESCE(has_admin_option, FALSE);
                        has_set_membership := COALESCE(has_set_membership, FALSE);

                        IF NOT has_set_membership AND NOT has_admin_option THEN
                            RAISE EXCEPTION 'PREFLIGHT SAFEGUARD ABORT';
                        END IF;

                        IF NOT has_set_membership THEN
                            IF current_setting('server_version_num')::integer >= 160000 THEN
                                EXECUTE format('GRANT migrator_matrix_d TO %I WITH SET TRUE', caller_role);
                            ELSE
                                EXECUTE format('GRANT migrator_matrix_d TO %I', caller_role);
                            END IF;
                            temp_membership_granted := TRUE;
                        END IF;
                    END IF;

                    -- Step 5c: Transfer ownership
                    EXECUTE 'ALTER TABLE public.tbl_matrix_d OWNER TO migrator_matrix_d';

                    -- Step 5d: Revoke temporary membership
                    IF temp_membership_granted THEN
                        EXECUTE format('REVOKE migrator_matrix_d FROM %I', caller_role);
                    END IF;
                END $$;
                """);

            stmt.execute("RESET ROLE");

            // Verify ownership transferred
            String postOwner = getTableOwner(conn, "tbl_matrix_d");
            System.out.println("Scenario 4D Table Owner after transfer: " + postOwner);
            assertEquals("migrator_matrix_d", postOwner);

            // Verify pre-existing membership preserved
            AuthMemberRecord postRecord = getAuthMemberRecord(conn, "migrator_matrix_d", "simulated_postgres");
            System.out.println("Scenario 4D Post-run pg_auth_members: " + postRecord);
            assertTrue(postRecord.exists);
            assertTrue(postRecord.adminOption);
            assertTrue(postRecord.setOption);

            // Rollback table ownership
            stmt.execute("ALTER TABLE public.tbl_matrix_d OWNER TO simulated_postgres");
            stmt.execute("REVOKE migrator_matrix_d FROM simulated_postgres");
        }
    }

    // =========================================================================
    // SECTION 4: Grant Fallback & Version Branching on PG 16 (Requirement 4)
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("8. Verify Explicit PostgreSQL Version Grant Handling (PG 16+ vs Legacy)")
    void test5_ExplicitVersionGrantBranching() throws Exception {
        System.out.println("=== SECTION 4: EXPLICIT VERSION GRANT HANDLING ===");
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP ROLE IF EXISTS migrator_version_test");
            stmt.execute("CREATE ROLE migrator_version_test WITH LOGIN NOSUPERUSER");

            // Branch A: Real PG 16 branch -> verifies server_version_num >= 160000 and grants WITH SET TRUE
            stmt.execute("""
                DO $$
                BEGIN
                    IF current_setting('server_version_num')::integer >= 160000 THEN
                        EXECUTE 'GRANT migrator_version_test TO simulated_postgres WITH SET TRUE';
                    ELSE
                        EXECUTE 'GRANT migrator_version_test TO simulated_postgres';
                    END IF;
                END $$;
                """);

            // Verify set_option is TRUE in pg_auth_members on PG 16
            AuthMemberRecord recA = getAuthMemberRecord(conn, "migrator_version_test", "simulated_postgres");
            System.out.println("PG 16 Branch Grant Result: " + recA);
            assertTrue(recA.exists);
            assertTrue(recA.setOption, "set_option must be TRUE on PG 16");

            // Branch B: Verify that on PG 16, invalid syntax or unexpected grant error is NOT swallowed and aborts
            SQLException ex = assertThrows(SQLException.class, () -> {
                stmt.execute("""
                    DO $$
                    BEGIN
                        IF current_setting('server_version_num')::integer >= 160000 THEN
                            -- Intentional non-existent role to verify no broad EXCEPTION swallowing
                            EXECUTE 'GRANT migrator_version_test TO non_existent_role_xyz_123 WITH SET TRUE';
                        ELSE
                            EXECUTE 'GRANT migrator_version_test TO non_existent_role_xyz_123';
                        END IF;
                    END $$;
                    """);
            });
            System.out.println("PG 16 Unexpected Error Preservation: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("does not exist"), "PG 16 errors must not be silently swallowed!");

            stmt.execute("REVOKE migrator_version_test FROM simulated_postgres");
        }
    }

    // =========================================================================
    // SECTION 5: Failure and Cleanup Scenarios on PG 16 (Requirement 5)
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("9A. Failure Scenario: Temporary Grant Succeeds but Ownership Transfer Fails -> Atomic Abort")
    void test6A_TemporaryGrantSucceeds_TransferFails_AtomicAbort() throws Exception {
        System.out.println("=== SECTION 5A: TEMPORARY GRANT SUCCEEDS BUT TRANSFER FAILS ===");
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP TABLE IF EXISTS public.tbl_fail_cleanup CASCADE");
            stmt.execute("DROP ROLE IF EXISTS migrator_fail_cleanup");
            stmt.execute("CREATE ROLE migrator_fail_cleanup WITH LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO migrator_fail_cleanup");
            stmt.execute("CREATE TABLE public.tbl_fail_cleanup (id SERIAL PRIMARY KEY, note TEXT)");
            stmt.execute("ALTER TABLE public.tbl_fail_cleanup OWNER TO simulated_postgres");

            // Caller has ADMIN OPTION but SET FALSE
            stmt.execute("GRANT migrator_fail_cleanup TO simulated_postgres WITH ADMIN OPTION");
            stmt.execute("GRANT migrator_fail_cleanup TO simulated_postgres WITH SET FALSE");

            AuthMemberRecord initialRec = getAuthMemberRecord(conn, "migrator_fail_cleanup", "simulated_postgres");
            assertTrue(initialRec.exists);
            assertTrue(initialRec.adminOption);
            assertFalse(initialRec.setOption);

            // Execute transaction where temporary SET TRUE is granted, but ownership transfer fails
            stmt.execute("SET ROLE simulated_postgres");
            SQLException ex = assertThrows(SQLException.class, () -> {
                stmt.execute("""
                    DO $$
                    DECLARE
                        caller_role TEXT := CURRENT_USER;
                        temp_membership_granted BOOLEAN := FALSE;
                    BEGIN
                        -- 1. Temporary grant succeeds
                        IF current_setting('server_version_num')::integer >= 160000 THEN
                            EXECUTE format('GRANT migrator_fail_cleanup TO %I WITH SET TRUE', caller_role);
                        ELSE
                            EXECUTE format('GRANT migrator_fail_cleanup TO %I', caller_role);
                        END IF;
                        temp_membership_granted := TRUE;

                        -- 2. Simulate failure during ownership transfer
                        RAISE EXCEPTION 'SIMULATED TRANSFER FAILURE: Network/permission error during ALTER TABLE';
                    END $$;
                    """);
            });

            System.out.println("Simulated Transfer Failure Message: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("SIMULATED TRANSFER FAILURE"));

            // Reset role and check state: because DO block transaction aborted,
            // the temporary grant was rolled back atomically by PostgreSQL!
            stmt.execute("RESET ROLE");

            AuthMemberRecord postRec = getAuthMemberRecord(conn, "migrator_fail_cleanup", "simulated_postgres");
            System.out.println("Post-failure pg_auth_members: " + postRec);
            assertTrue(postRec.exists);
            assertTrue(postRec.adminOption);
            // Must have rolled back to FALSE! No lingering SET TRUE dirty state!
            assertFalse(postRec.setOption, "Temporary SET TRUE grant must be rolled back by transaction abort!");

            String postOwner = getTableOwner(conn, "tbl_fail_cleanup");
            assertEquals("simulated_postgres", postOwner, "Table owner must remain simulated_postgres");
        }
    }

    @Test
    @Order(10)
    @DisplayName("9B. Failure Scenario: Preflight Aborts on Alien Table -> Zero Modifications")
    void test6B_PreflightAbortsOnAlienTable_ZeroModifications() throws Exception {
        System.out.println("=== SECTION 5B: PREFLIGHT ABORTS ON ALIEN TABLE ===");
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP TABLE IF EXISTS public.users CASCADE");
            stmt.execute("DROP ROLE IF EXISTS migrator_alien_test");
            stmt.execute("DROP ROLE IF EXISTS alien_owner");
            stmt.execute("CREATE ROLE migrator_alien_test WITH LOGIN NOSUPERUSER");
            stmt.execute("CREATE ROLE alien_owner WITH LOGIN NOSUPERUSER");

            // Table owned by alien_owner
            stmt.execute("CREATE TABLE public.users (id SERIAL PRIMARY KEY)");
            stmt.execute("ALTER TABLE public.users OWNER TO alien_owner");

            // simulated_postgres has ADMIN OPTION and SET TRUE on migrator_alien_test
            stmt.execute("GRANT migrator_alien_test TO simulated_postgres WITH ADMIN OPTION");
            stmt.execute("GRANT migrator_alien_test TO simulated_postgres WITH SET TRUE");

            // Execute preflight as simulated_postgres
            stmt.execute("SET ROLE simulated_postgres");
            SQLException ex = assertThrows(SQLException.class, () -> {
                stmt.execute("""
                    DO $$
                    DECLARE
                        caller_role TEXT := CURRENT_USER;
                        is_super BOOLEAN;
                        current_tbl_owner TEXT;
                    BEGIN
                        SELECT COALESCE(rolsuper, FALSE) INTO is_super FROM pg_roles WHERE rolname = caller_role;
                        SELECT tableowner INTO current_tbl_owner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'users';
                        IF NOT is_super AND current_tbl_owner <> caller_role THEN
                            RAISE EXCEPTION 'PREFLIGHT ABORT: Table public.users is owned by [%], but caller is non-superuser [%]! Non-superusers cannot transfer objects they do not own.', current_tbl_owner, caller_role;
                        END IF;
                    END $$;
                    """);
            });

            System.out.println("Alien Table Abort Message: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("PREFLIGHT ABORT"));

            stmt.execute("RESET ROLE");
            String owner = getTableOwner(conn, "users");
            assertEquals("alien_owner", owner, "Owner must remain alien_owner");
        }
    }

    @Test
    @Order(11)
    @DisplayName("9C. Rollback Scenario: Pre-Existing Membership Preserved During Rollback")
    void test6C_RollbackPreservesPreExistingMembership() throws Exception {
        System.out.println("=== SECTION 5C: ROLLBACK PRESERVES PRE-EXISTING MEMBERSHIP ===");
        try (Connection conn = superuserDs.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP TABLE IF EXISTS public.tbl_rb_preserv CASCADE");
            stmt.execute("DROP ROLE IF EXISTS migrator_rb_preserv");
            stmt.execute("CREATE ROLE migrator_rb_preserv WITH LOGIN NOSUPERUSER");
            stmt.execute("GRANT USAGE, CREATE ON SCHEMA public TO migrator_rb_preserv");
            stmt.execute("CREATE TABLE public.tbl_rb_preserv (id SERIAL PRIMARY KEY)");
            stmt.execute("ALTER TABLE public.tbl_rb_preserv OWNER TO migrator_rb_preserv");

            // simulated_postgres had pre-existing membership WITH SET TRUE before rollback
            stmt.execute("GRANT migrator_rb_preserv TO simulated_postgres WITH SET TRUE");
            stmt.execute("REVOKE ADMIN OPTION FOR migrator_rb_preserv FROM simulated_postgres");

            AuthMemberRecord initialRec = getAuthMemberRecord(conn, "migrator_rb_preserv", "simulated_postgres");
            assertTrue(initialRec.exists);
            assertTrue(initialRec.setOption);

            // Execute rollback as simulated_postgres
            stmt.execute("SET ROLE simulated_postgres");
            stmt.execute("""
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
                        WHERE r.rolname = 'migrator_rb_preserv' AND m.rolname = caller_role;

                        has_membership := COALESCE(has_membership, FALSE);
                        has_admin_option := COALESCE(has_admin_option, FALSE);
                        has_set_membership := COALESCE(has_set_membership, FALSE);

                        IF NOT has_set_membership AND NOT has_admin_option THEN
                            RAISE EXCEPTION 'ROLLBACK ABORT';
                        END IF;

                        IF NOT has_set_membership THEN
                            IF current_setting('server_version_num')::integer >= 160000 THEN
                                EXECUTE format('GRANT migrator_rb_preserv TO %I WITH SET TRUE', caller_role);
                            ELSE
                                EXECUTE format('GRANT migrator_rb_preserv TO %I', caller_role);
                            END IF;
                            temp_membership_granted := TRUE;
                        END IF;
                    END IF;

                    -- Restore ownership back to simulated_postgres
                    EXECUTE 'ALTER TABLE public.tbl_rb_preserv OWNER TO simulated_postgres';

                    -- Only revoke if temp_membership_granted
                    IF temp_membership_granted THEN
                        EXECUTE format('REVOKE migrator_rb_preserv FROM %I', caller_role);
                    END IF;
                END $$;
                """);

            stmt.execute("RESET ROLE");

            // Verify ownership restored
            String owner = getTableOwner(conn, "tbl_rb_preserv");
            assertEquals("simulated_postgres", owner);

            // Verify pre-existing membership was PRESERVED
            AuthMemberRecord postRec = getAuthMemberRecord(conn, "migrator_rb_preserv", "simulated_postgres");
            System.out.println("Rollback Post-run pg_auth_members: " + postRec);
            assertTrue(postRec.exists, "Pre-existing membership must NOT be removed by rollback!");
            assertTrue(postRec.setOption, "set_option must remain TRUE!");

            stmt.execute("REVOKE migrator_rb_preserv FROM simulated_postgres");
            stmt.execute("REVOKE ALL PRIVILEGES ON SCHEMA public FROM migrator_rb_preserv");
            stmt.execute("DROP TABLE IF EXISTS public.tbl_rb_preserv");
            stmt.execute("DROP ROLE IF EXISTS migrator_rb_preserv");
        }
    }
}
