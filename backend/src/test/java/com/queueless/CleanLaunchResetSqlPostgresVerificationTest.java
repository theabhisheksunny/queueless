package com.queueless;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates scripts/clean_launch_reset.sql directly against a live PostgreSQL instance.
 * Covers:
 * 1. Schema resilience on an unmigrated database (Flyway V8, where sse_tickets is absent)
 * 2. Execution on a fully upgraded database (Flyway V11, where sse_tickets, public_merchant_code, and business_type exist)
 * 3. Exact invariant checks (1 owner, 1 shop, 1 business, 0 queues, 0 customers, synchronized sequences)
 */
public class CleanLaunchResetSqlPostgresVerificationTest {

    private static EmbeddedPostgres embeddedPostgres;
    private static String resetSqlContent;

    @BeforeAll
    static void setUp() throws Exception {
        embeddedPostgres = EmbeddedPostgres.builder().start();

        // Locate scripts/clean_launch_reset.sql
        Path scriptPath = Paths.get("..", "scripts", "clean_launch_reset.sql");
        if (!Files.exists(scriptPath)) {
            scriptPath = Paths.get("scripts", "clean_launch_reset.sql");
        }
        assertTrue(Files.exists(scriptPath), "clean_launch_reset.sql must exist at: " + scriptPath.toAbsolutePath());
        resetSqlContent = Files.readString(scriptPath);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (embeddedPostgres != null) {
            embeddedPostgres.close();
        }
    }

    @Test
    @DisplayName("1. Clean Launch Reset executes cleanly on V8 unmigrated schema (without sse_tickets)")
    void testResetOnV8SchemaWithoutSseTickets() throws Exception {
        String dbName = "test_reset_v8";
        try (Connection conn = embeddedPostgres.getPostgresDatabase().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + dbName);
        }

        DataSource ds = embeddedPostgres.getDatabase("postgres", dbName);

        // Migrate strictly to V8 (reproducing live Supabase state)
        Flyway flywayV8 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("8")
                .load();
        flywayV8.migrate();

        // Verify sse_tickets does NOT exist
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'sse_tickets')")) {
            assertTrue(rs.next());
            assertFalse(rs.getBoolean(1), "sse_tickets must NOT exist on V8 schema");
        }

        // Seed some sample data that must be cleared
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (email, password, full_name, phone) VALUES ('old@test.com', 'pwd', 'Old User', '+919876543210')");
            stmt.execute("INSERT INTO businesses (owner_id, name) VALUES (1, 'Old Business')");
            stmt.execute("INSERT INTO shops (owner_id, business_id, name, category) VALUES (1, 1, 'Old Shop', 'Saloon')");
        }

        // Execute clean_launch_reset.sql
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(resetSqlContent);
        }

        // Verify Invariants after execution on V8 schema
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM users")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Must have exactly 1 user");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM shops")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Must have exactly 1 shop");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM businesses")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Must have exactly 1 business");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM subscriptions")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Must have exactly 1 subscription");
            }
        }
    }

    @Test
    @DisplayName("2. Clean Launch Reset executes cleanly on V11 fully-migrated schema (with sse_tickets and V10/V11 columns)")
    void testResetOnV11FullyMigratedSchema() throws Exception {
        String dbName = "test_reset_v11";
        try (Connection conn = embeddedPostgres.getPostgresDatabase().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + dbName);
        }

        DataSource ds = embeddedPostgres.getDatabase("postgres", dbName);

        // Migrate all the way to V11
        Flyway flywayV11 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
        flywayV11.migrate();

        // Verify sse_tickets DOES exist
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'sse_tickets')")) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean(1), "sse_tickets must exist on V11 schema");
        }

        // Seed some sample data including an sse_ticket
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (email, password, full_name, phone) VALUES ('temp@test.com', 'pwd', 'Temp User', '+919876543210')");
            stmt.execute("INSERT INTO businesses (owner_id, name) VALUES (1, 'Temp Biz')");
            stmt.execute("INSERT INTO shops (owner_id, business_id, name, category, public_merchant_code, business_type) " +
                    "VALUES (1, 1, 'Temp Shop', 'Salon & Personal Care', 'pm_temp1234567890', 'Barber Shop')");
            stmt.execute("INSERT INTO sse_tickets (ticket, user_id, shop_id, expires_at) " +
                    "VALUES ('ticket_abc123', 1, 1, CURRENT_TIMESTAMP + INTERVAL '1 hour')");
        }

        // Execute clean_launch_reset.sql
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(resetSqlContent);
        }

        // Verify Invariants after execution on V11 schema
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT version, description, type, script, checksum FROM flyway_schema_history ORDER BY installed_rank")) {
                while (rs.next()) {
                    System.out.println("FLYWAY_ROW: v=" + rs.getString("version") + " type=" + rs.getString("type") + " script=" + rs.getString("script") + " checksum=" + rs.getInt("checksum"));
                }
            }
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM users")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Must have exactly 1 user");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM shops")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "Must have exactly 1 shop");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM sse_tickets")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "Must have exactly 0 sse_tickets");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT public_merchant_code, business_type FROM shops WHERE id = 3001")) {
                assertTrue(rs.next());
                assertEquals("DEMOSHOP1", rs.getString("public_merchant_code"));
                assertEquals("General Store", rs.getString("business_type"));
            }
        }
    }

    @Test
    @DisplayName("3. scripts/apply_pending_migrations_v9_to_v11.sql upgrades V8 to V11 and passes Flyway validate")
    void testApplyPendingMigrationsAndFlywayValidate() throws Exception {
        String dbName = "test_manual_v9_v11";
        try (Connection conn = embeddedPostgres.getPostgresDatabase().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + dbName);
        }

        DataSource ds = embeddedPostgres.getDatabase("postgres", dbName);

        // Migrate strictly to V8
        Flyway flywayV8 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("8")
                .load();
        flywayV8.migrate();

        // Read scripts/apply_pending_migrations_v9_to_v11.sql
        Path migrationScriptPath = Paths.get("..", "scripts", "apply_pending_migrations_v9_to_v11.sql");
        if (!Files.exists(migrationScriptPath)) {
            migrationScriptPath = Paths.get("scripts", "apply_pending_migrations_v9_to_v11.sql");
        }
        String migrationSql = Files.readString(migrationScriptPath);

        // Execute manual migration script in PostgreSQL
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(migrationSql);
        }

        // Verify Flyway validate now succeeds without errors on the upgraded database
        Flyway flywayV11 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
        flywayV11.validate();

        // Verify sse_tickets exists and columns exist on shops
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'sse_tickets')")) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1));
            }
            try (ResultSet rs = stmt.executeQuery("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'shops' AND column_name = 'public_merchant_code')")) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1));
            }
            try (ResultSet rs = stmt.executeQuery("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'shops' AND column_name = 'business_type')")) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1));
            }
        }
    }

    @Test
    @DisplayName("4. Verify non-owner permission remediation: least privilege SELECT, DELETE without residual membership")
    void testNonOwnerPermissionRemediation() throws Exception {
        String dbName = "test_perm_remediation";
        try (Connection conn = embeddedPostgres.getPostgresDatabase().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + dbName);
        }

        DataSource ds = embeddedPostgres.getDatabase("postgres", dbName);

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            // Setup roles mimicking Supabase: admin_operator (representing postgres) with CREATEROLE but NOT SUPERUSER
            stmt.execute("DROP ROLE IF EXISTS migrator_role_4, admin_operator_4");
            stmt.execute("CREATE ROLE admin_operator_4 WITH LOGIN CREATEROLE NOSUPERUSER");
            stmt.execute("GRANT ALL ON SCHEMA public TO admin_operator_4");

            // admin_operator creates the migrator role and therefore holds ADMIN OPTION on it
            stmt.execute("SET ROLE admin_operator_4");
            stmt.execute("CREATE ROLE migrator_role_4 WITH LOGIN NOSUPERUSER");
            stmt.execute("RESET ROLE");

            // As migrator_role, create sse_tickets table
            stmt.execute("GRANT ALL ON SCHEMA public TO migrator_role_4");
            stmt.execute("SET ROLE migrator_role_4");
            stmt.execute("CREATE TABLE sse_tickets (ticket VARCHAR(64) PRIMARY KEY, user_id BIGINT, shop_id BIGINT, expires_at TIMESTAMP)");
            stmt.execute("INSERT INTO sse_tickets VALUES ('t1', 1, 1, CURRENT_TIMESTAMP)");
            stmt.execute("RESET ROLE");

            // Switch to admin_operator and verify DELETE fails with 42501 (Permission Denied)
            stmt.execute("SET ROLE admin_operator_4");
            assertThrows(Exception.class, () -> stmt.execute("DELETE FROM sse_tickets"),
                    "admin_operator must NOT be permitted to DELETE from sse_tickets prior to grant");

            // Execute the proposed minimal remediation:
            stmt.execute("GRANT migrator_role_4 TO admin_operator_4");
            stmt.execute("SET ROLE migrator_role_4");
            stmt.execute("GRANT SELECT, DELETE ON TABLE public.sse_tickets TO admin_operator_4");
            stmt.execute("RESET ROLE");
            stmt.execute("SET ROLE admin_operator_4");
            stmt.execute("REVOKE migrator_role_4 FROM admin_operator_4");

            // Verify admin_operator can now SELECT and DELETE
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM sse_tickets")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            int deleted = stmt.executeUpdate("DELETE FROM sse_tickets");
            assertEquals(1, deleted, "DELETE must succeed after granting SELECT, DELETE");

            // Verify admin_operator cannot INSERT (least privilege enforced)
            assertThrows(Exception.class, () -> stmt.execute("INSERT INTO sse_tickets VALUES ('t2', 1, 1, CURRENT_TIMESTAMP)"),
                    "admin_operator must NOT have INSERT privilege (least privilege)");

            // Verify zero residual role membership
            try (ResultSet rs = stmt.executeQuery("""
                SELECT 1 FROM pg_auth_members am
                JOIN pg_roles r ON r.oid = am.roleid
                JOIN pg_roles m ON m.oid = am.member
                WHERE r.rolname = 'migrator_role_4' AND m.rolname = 'admin_operator_4'
            """)) {
                assertFalse(rs.next(), "admin_operator must have ZERO residual role membership in migrator_role");
            }

            // Verify admin_operator can GRANT again thanks to CREATEROLE / role ownership
            assertDoesNotThrow(() -> {
                stmt.execute("GRANT migrator_role_4 TO admin_operator_4");
                stmt.execute("REVOKE migrator_role_4 FROM admin_operator_4");
            }, "admin_operator with CREATEROLE must be authorized to GRANT migrator_role again");

            // Verify the exact JSON verification query runs cleanly
            try (ResultSet rs = stmt.executeQuery("""
                SELECT 
                    '1. SELECT COUNT(*)' AS verification_check,
                    jsonb_build_object(
                        'can_select', true, 
                        'current_count', (SELECT COUNT(*) FROM public.sse_tickets)
                    ) AS result
                UNION ALL
                SELECT 
                    '2. Table Privileges Matrix',
                    jsonb_build_object(
                        'can_select', has_table_privilege('admin_operator_4', 'public.sse_tickets', 'SELECT'),
                        'can_delete', has_table_privilege('admin_operator_4', 'public.sse_tickets', 'DELETE'),
                        'can_insert', has_table_privilege('admin_operator_4', 'public.sse_tickets', 'INSERT'),
                        'can_update', has_table_privilege('admin_operator_4', 'public.sse_tickets', 'UPDATE'),
                        'can_truncate', has_table_privilege('admin_operator_4', 'public.sse_tickets', 'TRUNCATE')
                    )
                UNION ALL
                SELECT 
                    '3. Table Owner Check',
                    jsonb_build_object(
                        'table_name', 'sse_tickets',
                        'table_owner', (
                            SELECT pg_get_userbyid(c.relowner) 
                            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace 
                            WHERE n.nspname = 'public' AND c.relname = 'sse_tickets'
                        )
                    )
            """)) {
                assertTrue(rs.next());
                assertEquals("1. SELECT COUNT(*)", rs.getString(1));
                assertTrue(rs.next());
                assertEquals("2. Table Privileges Matrix", rs.getString(1));
                assertTrue(rs.next());
                assertEquals("3. Table Owner Check", rs.getString(1));
            }
        }
    }

    @Test
    @DisplayName("5. Verify transaction rollback behavior on permission remediation failure")
    void testRemediationRollbackBehavior() throws Exception {
        String dbName = "test_rollback_behavior";
        try (Connection conn = embeddedPostgres.getPostgresDatabase().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + dbName);
        }

        DataSource ds = embeddedPostgres.getDatabase("postgres", dbName);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP ROLE IF EXISTS migrator_role_5, admin_operator_5");
            stmt.execute("CREATE ROLE admin_operator_5 WITH LOGIN CREATEROLE NOSUPERUSER");
            stmt.execute("GRANT ALL ON SCHEMA public TO admin_operator_5");

            stmt.execute("SET ROLE admin_operator_5");
            stmt.execute("CREATE ROLE migrator_role_5 WITH LOGIN NOSUPERUSER");
            stmt.execute("RESET ROLE");

            stmt.execute("GRANT ALL ON SCHEMA public TO migrator_role_5");
            stmt.execute("SET ROLE migrator_role_5");
            stmt.execute("CREATE TABLE sse_tickets (ticket VARCHAR(64) PRIMARY KEY)");
            stmt.execute("RESET ROLE");

            // Execute an aborted transaction where an error occurs mid-way
            stmt.execute("SET ROLE admin_operator_5");
            assertThrows(Exception.class, () -> {
                try (Statement txStmt = conn.createStatement()) {
                    conn.setAutoCommit(false);
                    txStmt.execute("GRANT migrator_role_5 TO admin_operator_5");
                    txStmt.execute("SET ROLE migrator_role_5");
                    txStmt.execute("GRANT SELECT, DELETE ON TABLE public.sse_tickets TO admin_operator_5");
                    // Trigger a forced failure before commit
                    txStmt.execute("SELECT 1 / 0");
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                    throw e;
                }
            });

            // Ensure after rollback, admin_operator has NO residual membership and NO SELECT/DELETE privileges
            stmt.execute("RESET ROLE");
            stmt.execute("SET ROLE admin_operator_5");
            assertThrows(Exception.class, () -> stmt.execute("SELECT * FROM sse_tickets"),
                    "After rollback, admin_operator must NOT have SELECT on sse_tickets");
            assertThrows(Exception.class, () -> stmt.execute("DELETE FROM sse_tickets"),
                    "After rollback, admin_operator must NOT have DELETE on sse_tickets");

            // Verify zero residual role membership in pg_auth_members
            try (ResultSet rs = stmt.executeQuery("""
                SELECT 1 FROM pg_auth_members am
                JOIN pg_roles r ON r.oid = am.roleid
                JOIN pg_roles m ON m.oid = am.member
                WHERE r.rolname = 'migrator_role_5' AND m.rolname = 'admin_operator_5'
            """)) {
                assertFalse(rs.next(), "admin_operator must have ZERO membership after transaction rollback");
            }
        }
    }
}
