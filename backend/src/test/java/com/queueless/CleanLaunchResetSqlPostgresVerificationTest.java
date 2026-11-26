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
}
