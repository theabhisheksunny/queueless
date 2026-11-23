package com.queueless;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates QueueLess database migrations against a real, isolated PostgreSQL database.
 * Verifies catalog state, constraints, indexes, and data retention under PostgreSQL semantics.
 */
public class PostgreSqlMigrationAndConstraintVerificationTest {

    private static EmbeddedPostgres embeddedPostgres;
    private static DataSource dataSource;
    private static String postgresVersion;

    @BeforeAll
    static void setUp() throws Exception {
        embeddedPostgres = EmbeddedPostgres.builder().start();
        dataSource = embeddedPostgres.getPostgresDatabase();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version()")) {
            if (rs.next()) {
                postgresVersion = rs.getString(1);
            }
        }
        System.out.println(">>> Running migration verification on: " + postgresVersion);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (embeddedPostgres != null) {
            embeddedPostgres.close();
        }
    }

    @Test
    @DisplayName("1. Run all Flyway migrations V1->V7 on clean PostgreSQL and verify catalog constraints")
    void testFlywayMigrationsOnCleanPostgreSQL() throws Exception {
        // Run Flyway V1 -> V7
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertTrue(migrationsApplied >= 6, "All migrations must execute successfully on clean PostgreSQL");

        try (Connection conn = dataSource.getConnection()) {
            // 1. Verify NO unique constraint remains on shops.owner_id
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT con.conname, con.contype " +
                         "FROM pg_constraint con " +
                         "JOIN pg_class rel ON rel.oid = con.conrelid " +
                         "JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY(con.conkey) " +
                         "WHERE rel.relname = 'shops' " +
                         "  AND att.attname = 'owner_id' " +
                         "  AND con.contype = 'u'")) {
                assertFalse(rs.next(), "No UNIQUE constraint ('u') must exist on shops.owner_id in PostgreSQL catalog");
            }

            // 2. Verify primary key on shops.id is intact ('p')
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT con.conname " +
                         "FROM pg_constraint con " +
                         "JOIN pg_class rel ON rel.oid = con.conrelid " +
                         "JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY(con.conkey) " +
                         "WHERE rel.relname = 'shops' " +
                         "  AND att.attname = 'id' " +
                         "  AND con.contype = 'p'")) {
                assertTrue(rs.next(), "Primary key constraint ('p') must remain intact on shops.id");
                assertEquals("shops_pkey", rs.getString("conname"));
            }

            // 3. Verify Foreign Key from shops.owner_id to users.id is intact ('f')
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT con.conname " +
                         "FROM pg_constraint con " +
                         "JOIN pg_class rel ON rel.oid = con.conrelid " +
                         "JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY(con.conkey) " +
                         "WHERE rel.relname = 'shops' " +
                         "  AND att.attname = 'owner_id' " +
                         "  AND con.contype = 'f'")) {
                assertTrue(rs.next(), "Foreign key constraint ('f') from shops.owner_id -> users.id must be preserved");
            }

            // 4. Verify idx_shops_owner index is preserved as non-unique
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT i.relname AS index_name, idx.indisunique " +
                         "FROM pg_index idx " +
                         "JOIN pg_class t ON t.oid = idx.indrelid " +
                         "JOIN pg_class i ON i.oid = idx.indexrelid " +
                         "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(idx.indkey) " +
                         "WHERE t.relname = 'shops' AND a.attname = 'owner_id'")) {
                boolean foundOwnerIndex = false;
                while (rs.next()) {
                    String idxName = rs.getString("index_name");
                    boolean isUnique = rs.getBoolean("indisunique");
                    if ("idx_shops_owner".equalsIgnoreCase(idxName)) {
                        foundOwnerIndex = true;
                        assertFalse(isUnique, "idx_shops_owner must be non-unique");
                    } else {
                        // Any other index on owner_id must also not be unique
                        assertFalse(isUnique, "Index " + idxName + " on shops.owner_id must NOT be unique");
                    }
                }
                assertTrue(foundOwnerIndex, "idx_shops_owner non-unique index must exist in PostgreSQL");
            }

            // 5. Verify idx_shops_business index exists
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT i.relname AS index_name " +
                         "FROM pg_index idx " +
                         "JOIN pg_class t ON t.oid = idx.indrelid " +
                         "JOIN pg_class i ON i.oid = idx.indexrelid " +
                         "WHERE t.relname = 'shops' AND i.relname = 'idx_shops_business'")) {
                assertTrue(rs.next(), "idx_shops_business index must exist");
            }
        }
    }

    @Test
    @DisplayName("2. Migration against database with original V1 UNIQUE constraint removes it and retains data")
    void testMigrationAgainstV1UniqueConstraintDatabase() throws Exception {
        // Create an isolated database simulating legacy state
        String legacyDbName = "legacy_test_db";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + legacyDbName);
        }

        DataSource legacyDs = embeddedPostgres.getDatabase("postgres", legacyDbName);

        // 1. Run Flyway targeted strictly at V1 (initial production schema with owner_id UNIQUE)
        Flyway v1Flyway = Flyway.configure()
                .dataSource(legacyDs)
                .locations("classpath:db/migration")
                .target("1")
                .load();
        v1Flyway.migrate();

        // Verify that in V1, PostgreSQL created the unique constraint on shops.owner_id
        try (Connection conn = legacyDs.getConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT con.conname " +
                         "FROM pg_constraint con " +
                         "JOIN pg_class rel ON rel.oid = con.conrelid " +
                         "JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY(con.conkey) " +
                         "WHERE rel.relname = 'shops' " +
                         "  AND att.attname = 'owner_id' " +
                         "  AND con.contype = 'u'")) {
                assertTrue(rs.next(), "V1 schema must create a UNIQUE constraint on shops.owner_id");
                assertEquals("shops_owner_id_key", rs.getString("conname"));
            }

            // Seed existing owner, shop, and subscription in V1
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO users (email, password, full_name, phone) " +
                        "VALUES ('legacy_owner@queueless.com', 'pwd_hash', 'Legacy Owner', '+919876543210')");
                stmt.execute("INSERT INTO shops (owner_id, name, category) " +
                        "VALUES (1, 'Legacy Main Salon', 'Barber Shop')");
                stmt.execute("INSERT INTO subscriptions (shop_id, plan_type, status, start_date, end_date, amount) " +
                        "VALUES (1, 'MONTHLY', 'ACTIVE', CURRENT_DATE, CURRENT_DATE + 30, 51.00)");
            }
        }

        // 2. Now run Flyway migrations through V7 on this populated legacy database
        Flyway upgradeFlyway = Flyway.configure()
                .dataSource(legacyDs)
                .locations("classpath:db/migration")
                .load();

        upgradeFlyway.migrate();

        // 3. Verify V7 dynamically and cleanly removed the unique constraint on shops.owner_id
        try (Connection conn = legacyDs.getConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT con.conname " +
                         "FROM pg_constraint con " +
                         "JOIN pg_class rel ON rel.oid = con.conrelid " +
                         "JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY(con.conkey) " +
                         "WHERE rel.relname = 'shops' " +
                         "  AND att.attname = 'owner_id' " +
                         "  AND con.contype = 'u'")) {
                assertFalse(rs.next(), "PostgreSQL unique constraint on shops.owner_id must be completely dropped by V7");
            }

            // 4. Verify existing record survived migration unchanged and was linked to backfilled business
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name, is_primary, registration_status, business_id FROM shops WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("Legacy Main Salon", rs.getString("name"));
                assertTrue(rs.getBoolean("is_primary"), "Existing shop must be backfilled as primary");
                assertEquals("REGISTERED", rs.getString("registration_status"));
                assertNotNull(rs.getObject("business_id"), "Existing shop must be linked to backfilled business");
            }

            // Verify subscription survived and has owner_id backfilled
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT owner_id, amount, status FROM subscriptions WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals(1L, rs.getLong("owner_id"));
                assertEquals("ACTIVE", rs.getString("status"));
            }

            // 5. Verify multiple branches for the SAME owner can now be created directly in PostgreSQL
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO shops (owner_id, business_id, name, branch_name, category, is_primary, registration_status) " +
                        "VALUES (1, 1, 'Legacy Main Salon', 'Branch 2 - Sector 18', 'Barber Shop', false, 'PAYMENT_PENDING')");
                stmt.execute("INSERT INTO shops (owner_id, business_id, name, branch_name, category, is_primary, registration_status) " +
                        "VALUES (1, 1, 'Legacy Main Salon', 'Branch 3 - Sector 62', 'Barber Shop', false, 'PAYMENT_PENDING')");
            }

            // 6. Verify all 3 shops exist for owner 1
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM shops WHERE owner_id = 1")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1), "Owner 1 must now have 3 branches in real PostgreSQL");
            }
        }
    }
}
