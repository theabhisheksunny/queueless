package com.queueless;

import com.queueless.config.DatabaseIsolationVerificationCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Database Isolation Verification Callback Tests")
class DatabaseIsolationVerificationCallbackTest {

    private Connection connection;
    private DatabaseIsolationVerificationCallback callback;
    private MockEnvironment mockEnvironment;

    @BeforeEach
    void setUp() throws Exception {
        mockEnvironment = new MockEnvironment();
        mockEnvironment.setActiveProfiles("staging");
        callback = new DatabaseIsolationVerificationCallback(mockEnvironment);
        callback.setCheckEnabled(true);

        // Create an isolated in-memory H2 database with a Supabase-compatible username
        String dbName = "test_isolation_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection("jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1", "postgres.stg-proj-valid", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    @DisplayName("Should extract Supabase project ref from username and pooler URL")
    void testExtractSupabaseProjectRef() {
        assertEquals("stg-ref-9988",
                DatabaseIsolationVerificationCallback.extractSupabaseProjectRef(
                        "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres",
                        "postgres.stg-ref-9988"));

        assertEquals("prod-ref-1122",
                DatabaseIsolationVerificationCallback.extractSupabaseProjectRef(
                        "jdbc:postgresql://db.prod-ref-1122.supabase.co:5432/postgres",
                        "postgres"));

        assertEquals("custom-ref-3344",
                DatabaseIsolationVerificationCallback.extractSupabaseProjectRef(
                        "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:6543/postgres",
                        "app_user.custom-ref-3344"));

        assertEquals("stg-ref-queueless",
                DatabaseIsolationVerificationCallback.extractSupabaseProjectRef(
                        "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres",
                        "queueless_app_staging.stg-ref-queueless"));

        assertEquals("prod-ref-queueless",
                DatabaseIsolationVerificationCallback.extractSupabaseProjectRef(
                        "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres",
                        "queueless_app_prod.prod-ref-queueless"));

        assertEquals("migrator-ref",
                DatabaseIsolationVerificationCallback.extractSupabaseProjectRef(
                        "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres",
                        "queueless_migrator_staging.migrator-ref"));

        assertNull(DatabaseIsolationVerificationCallback.extractSupabaseProjectRef(
                "jdbc:postgresql://localhost:5432/queueless",
                "postgres"));
    }

    @Test
    @DisplayName("Should fail closed when expected-environment-type is missing or blank (Base configuration fails closed)")
    void testMissingEnvironmentType_FailsClosed() {
        callback.setExpectedEnvironmentType("");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex.getMessage().contains("Missing required configuration 'app.datasource.expected-environment-type'"),
                "Expected failure message regarding missing environment type, but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Should fail closed on unrecognized environment type")
    void testUnrecognizedEnvironmentType_FailsClosed() {
        callback.setExpectedEnvironmentType("DEVELOPMENT_CUSTOM");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex.getMessage().contains("Unrecognized environment type"),
                "Expected failure on unrecognized environment type, but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Should fail closed when LOCAL environment type is used under cloud profiles (e.g. prod or staging)")
    void testLocalEnvironment_UnderCloudProfile_FailsClosed() {
        // Attempt to bypass canary check in production by passing LOCAL
        mockEnvironment.setActiveProfiles("prod");
        callback.setExpectedEnvironmentType("LOCAL");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex.getMessage().contains("CRITICAL DATABASE ISOLATION VIOLATION: Environment type LOCAL is strictly prohibited when running under cloud profiles"),
                "Expected rejection of LOCAL under prod profile, but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Should fail closed when cloud environments (STAGING or PRODUCTION) have empty expected project ref")
    void testCloudEnvironment_WithoutProjectRef_FailsClosed() {
        // Staging without project ref
        callback.setExpectedEnvironmentType("STAGING");
        callback.setExpectedProjectRef("");

        SecurityException ex1 = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex1.getMessage().contains("Missing required configuration 'app.datasource.expected-project-ref' for cloud environment 'STAGING'"),
                "Expected failure on missing project ref for staging, but got: " + ex1.getMessage());

        // Production without project ref
        mockEnvironment.setActiveProfiles("prod");
        callback.setExpectedEnvironmentType("PRODUCTION");
        callback.setExpectedProjectRef(null);

        SecurityException ex2 = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex2.getMessage().contains("Missing required configuration 'app.datasource.expected-project-ref' for cloud environment 'PRODUCTION'"),
                "Expected failure on missing project ref for production, but got: " + ex2.getMessage());
    }

    @Test
    @DisplayName("Should fail closed when canary table is missing from target database")
    void testMissingCanaryTable_FailsClosed() {
        callback.setExpectedEnvironmentType("STAGING");
        callback.setExpectedProjectRef("stg-proj-valid");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex.getMessage().contains("Canary table '_system_environment_canary' does not exist"),
                "Expected failure message regarding missing canary table, but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Should fail closed when target database has a PRODUCTION canary under STAGING runtime")
    void testProductionCanary_UnderStaging_FailsClosed() throws Exception {
        callback.setExpectedEnvironmentType("STAGING");
        callback.setExpectedProjectRef("stg-proj-valid");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE _system_environment_canary (" +
                    "id INT PRIMARY KEY, " +
                    "environment_name VARCHAR(50) NOT NULL, " +
                    "supabase_project_ref VARCHAR(100) NOT NULL)");
            stmt.execute("INSERT INTO _system_environment_canary VALUES (1, 'PRODUCTION', 'stg-proj-valid')");
        }

        SecurityException ex = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex.getMessage().contains("Staging runtime attempted connection to a database tagged as PRODUCTION") ||
                   ex.getMessage().contains("Canary environment 'PRODUCTION' does not match expected environment 'STAGING'"),
                "Expected production canary violation message, but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Should fail closed when target database has a STAGING canary under PRODUCTION runtime")
    void testStagingCanary_UnderProduction_FailsClosed() throws Exception {
        mockEnvironment.setActiveProfiles("prod");
        callback.setExpectedEnvironmentType("PRODUCTION");
        callback.setExpectedProjectRef("prod-proj-valid");

        Connection prodConn = DriverManager.getConnection("jdbc:h2:mem:prod_test_db;DB_CLOSE_DELAY=-1", "postgres.prod-proj-valid", "");
        try {
            try (Statement stmt = prodConn.createStatement()) {
                stmt.execute("CREATE TABLE _system_environment_canary (" +
                        "id INT PRIMARY KEY, " +
                        "environment_name VARCHAR(50) NOT NULL, " +
                        "supabase_project_ref VARCHAR(100) NOT NULL)");
                stmt.execute("INSERT INTO _system_environment_canary VALUES (1, 'STAGING', 'prod-proj-valid')");
            }

            SecurityException ex = assertThrows(SecurityException.class, () ->
                    callback.verifyIsolation(prodConn));

            assertTrue(ex.getMessage().contains("Production runtime attempted connection to a database tagged as STAGING") ||
                       ex.getMessage().contains("Canary environment 'STAGING' does not match expected environment 'PRODUCTION'"),
                    "Expected staging canary under production violation, but got: " + ex.getMessage());
        } finally {
            prodConn.close();
        }
    }

    @Test
    @DisplayName("Should fail closed when canary table exists but has 0 records")
    void testEmptyCanaryTable_FailsClosed() throws Exception {
        callback.setExpectedEnvironmentType("STAGING");
        callback.setExpectedProjectRef("stg-proj-valid");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE _system_environment_canary (" +
                    "id INT PRIMARY KEY, " +
                    "environment_name VARCHAR(50) NOT NULL, " +
                    "supabase_project_ref VARCHAR(100) NOT NULL)");
        }

        SecurityException ex = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex.getMessage().contains("exists but contains no records"),
                "Expected empty canary table violation, but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Should fail closed when canary table contains multiple conflicting records")
    void testDuplicateCanaryRecords_FailsClosed() throws Exception {
        callback.setExpectedEnvironmentType("STAGING");
        callback.setExpectedProjectRef("stg-proj-valid");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE _system_environment_canary (" +
                    "id INT PRIMARY KEY, " +
                    "environment_name VARCHAR(50) NOT NULL, " +
                    "supabase_project_ref VARCHAR(100) NOT NULL)");
            stmt.execute("INSERT INTO _system_environment_canary VALUES (1, 'STAGING', 'stg-proj-valid')");
            stmt.execute("INSERT INTO _system_environment_canary VALUES (2, 'STAGING', 'stg-proj-valid')");
        }

        SecurityException ex = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex.getMessage().contains("multiple conflicting records"),
                "Expected multiple conflicting records violation, but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Should fail closed when canary table record has invalid primary key id != 1")
    void testTamperedCanaryRecordId_FailsClosed() throws Exception {
        callback.setExpectedEnvironmentType("STAGING");
        callback.setExpectedProjectRef("stg-proj-valid");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE _system_environment_canary (" +
                    "id INT PRIMARY KEY, " +
                    "environment_name VARCHAR(50) NOT NULL, " +
                    "supabase_project_ref VARCHAR(100) NOT NULL)");
            stmt.execute("INSERT INTO _system_environment_canary VALUES (99, 'STAGING', 'stg-proj-valid')");
        }

        SecurityException ex = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex.getMessage().contains("Canary record has invalid id=99"),
                "Expected invalid id violation, but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Should fail closed when connected project ref does not match expected project ref")
    void testProjectRefMismatchInUsername_FailsClosed() {
        callback.setExpectedEnvironmentType("STAGING");
        callback.setExpectedProjectRef("different-expected-ref");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex.getMessage().toLowerCase().contains("connected to project reference 'stg-proj-valid', but expected 'different-expected-ref'"),
                "Expected ref mismatch message, but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Should fail closed when target project ref matches a prohibited production ref")
    void testProhibitedProjectRef_FailsClosed() throws Exception {
        callback.setExpectedEnvironmentType("STAGING");
        callback.setExpectedProjectRef("prod-ref-forbidden");
        callback.setProhibitedProjectRefs("prod-ref-forbidden,prod-ref-critical");

        Connection connWithUser = DriverManager.getConnection(
                "jdbc:h2:mem:prohibited_test;DB_CLOSE_DELAY=-1", "postgres.prod-ref-forbidden", "");

        try {
            SecurityException ex = assertThrows(SecurityException.class, () ->
                    callback.verifyIsolation(connWithUser));

            assertTrue(ex.getMessage().contains("CRITICAL DATABASE ISOLATION VIOLATION: Connected to PROHIBITED project reference 'prod-ref-forbidden'"),
                    "Expected prohibited ref violation, but got: " + ex.getMessage());
        } finally {
            connWithUser.close();
        }
    }

    @Test
    @DisplayName("Should pass when canary table matches expected STAGING environment and project ref")
    void testValidStagingDatabase_Passes() throws Exception {
        callback.setExpectedEnvironmentType("STAGING");
        callback.setExpectedProjectRef("stg-proj-valid");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE _system_environment_canary (" +
                    "id INT PRIMARY KEY, " +
                    "environment_name VARCHAR(50) NOT NULL, " +
                    "supabase_project_ref VARCHAR(100) NOT NULL)");
            stmt.execute("INSERT INTO _system_environment_canary VALUES (1, 'STAGING', 'stg-proj-valid')");
        }

        // Must not throw any exception
        assertDoesNotThrow(() -> callback.verifyIsolation(connection));
    }

    @Test
    @DisplayName("Should pass when canary table matches expected PRODUCTION environment and project ref")
    void testValidProductionDatabase_Passes() throws Exception {
        mockEnvironment.setActiveProfiles("prod");
        callback.setExpectedEnvironmentType("PRODUCTION");
        callback.setExpectedProjectRef("prod-proj-valid");

        Connection prodConn = DriverManager.getConnection("jdbc:h2:mem:valid_prod_test;DB_CLOSE_DELAY=-1", "postgres.prod-proj-valid", "");
        try {
            try (Statement stmt = prodConn.createStatement()) {
                stmt.execute("CREATE TABLE _system_environment_canary (" +
                        "id INT PRIMARY KEY, " +
                        "environment_name VARCHAR(50) NOT NULL, " +
                        "supabase_project_ref VARCHAR(100) NOT NULL)");
                stmt.execute("INSERT INTO _system_environment_canary VALUES (1, 'PRODUCTION', 'prod-proj-valid')");
            }

            assertDoesNotThrow(() -> callback.verifyIsolation(prodConn));
        } finally {
            prodConn.close();
        }
    }

    @Test
    @DisplayName("Should safely allow local development bypass on local H2 profile")
    void testLocalH2Bypass_Passes() {
        mockEnvironment.setActiveProfiles("local");
        callback.setExpectedEnvironmentType("LOCAL");

        // Pure in-memory H2 with empty tables must bypass check on local profile
        assertDoesNotThrow(() -> callback.verifyIsolation(connection));
    }

    @Test
    @DisplayName("Should fail closed when production profile inherits missing or empty environment type from base defaults")
    void testProductionCannotBypassCanaryThroughInheritedDefaults() {
        mockEnvironment.setActiveProfiles("prod");
        // Simulating unconfigured/inherited default where no environment type was supplied
        callback.setExpectedEnvironmentType("");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex.getMessage().contains("Missing required configuration 'app.datasource.expected-environment-type'"),
                "Expected failure due to missing environment type in production, but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Should fail closed when production profile attempts to inherit or use LOCAL environment type")
    void testProductionAttemptingInheritedLocalDefault_FailsClosed() {
        mockEnvironment.setActiveProfiles("prod");
        callback.setExpectedEnvironmentType("LOCAL");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                callback.verifyIsolation(connection));

        assertTrue(ex.getMessage().contains("Environment type LOCAL is strictly prohibited when running under cloud profiles"),
                "Expected rejection of LOCAL under production, but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Should fail closed when production target canary environment has unknown or invalid environment tag")
    void testProductionWithInvalidCanaryTag_FailsClosed() throws Exception {
        mockEnvironment.setActiveProfiles("prod");
        callback.setExpectedEnvironmentType("PRODUCTION");
        callback.setExpectedProjectRef("prod-proj-valid");

        Connection prodConn = DriverManager.getConnection("jdbc:h2:mem:prod_invalid_tag_test;DB_CLOSE_DELAY=-1", "postgres.prod-proj-valid", "");
        try {
            try (Statement stmt = prodConn.createStatement()) {
                stmt.execute("CREATE TABLE _system_environment_canary (" +
                        "id INT PRIMARY KEY, " +
                        "environment_name VARCHAR(50) NOT NULL, " +
                        "supabase_project_ref VARCHAR(100) NOT NULL)");
                stmt.execute("INSERT INTO _system_environment_canary VALUES (1, 'DEV_TEST_CANARY', 'prod-proj-valid')");
            }

            SecurityException ex = assertThrows(SecurityException.class, () ->
                    callback.verifyIsolation(prodConn));

            assertTrue(ex.getMessage().contains("Canary environment 'DEV_TEST_CANARY' does not match expected environment 'PRODUCTION'"),
                    "Expected tag mismatch error, but got: " + ex.getMessage());
        } finally {
            prodConn.close();
        }
    }
}
