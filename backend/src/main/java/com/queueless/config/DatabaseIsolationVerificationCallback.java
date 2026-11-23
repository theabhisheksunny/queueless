package com.queueless.config;

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-Migration Database Isolation Verification Callback for Flyway.
 * <p>
 * Strictly enforces that the target database belongs to the intended environment
 * (e.g. STAGING vs PRODUCTION) BEFORE any Flyway migration statements are executed.
 * <p>
 * Key Security Guarantees:
 * 1. Base configuration fails closed: missing or invalid environment type aborts startup.
 * 2. LOCAL is permitted ONLY under explicitly activated local/test profiles and never under cloud profiles.
 * 3. Both STAGING and PRODUCTION explicitly mandate a non-empty expected Supabase project reference.
 * 4. Verifies Supabase session-pooler username credentials or direct pooler host.
 * 5. Queries the controlled _system_environment_canary table for environment identity.
 * 6. Never auto-initializes missing canary tables from application runtime (controlled bootstrap only).
 * 7. Enforces exactly one canary record with id=1; rejects 0, duplicate, or tampered records.
 * 8. Fails closed (throws SecurityException) on missing canary, mismatched ref, or cross-environment connection.
 */
@Component
public class DatabaseIsolationVerificationCallback implements Callback, FlywayConfigurationCustomizer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseIsolationVerificationCallback.class);

    private static final Set<String> ALLOWED_ENVIRONMENTS = new HashSet<>(Arrays.asList("LOCAL", "STAGING", "PRODUCTION"));

    private static final Pattern SUPABASE_URL_REF_PATTERN = Pattern.compile("db\\.([a-zA-Z0-9_-]+)\\.supabase\\.co", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPABASE_USER_REF_PATTERN = Pattern.compile("(?:postgres|[a-zA-Z0-9_-]+)\\.([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    private final Environment env;

    @Value("${app.datasource.isolation-check.enabled:true}")
    private boolean checkEnabled;

    @Value("${app.datasource.isolation-check.expected-environment-type:${app.datasource.expected-environment-type:}}")
    private String expectedEnvironmentType;

    @Value("${app.datasource.isolation-check.expected-project-ref:${app.datasource.expected-project-ref:}}")
    private String expectedProjectRef;

    @Value("${app.datasource.isolation-check.prohibited-project-refs:${app.datasource.prohibited-project-refs:}}")
    private String prohibitedProjectRefs;

    public DatabaseIsolationVerificationCallback(Environment env) {
        this.env = env;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return false;
    }

    @Override
    public String getCallbackName() {
        return "DatabaseIsolationVerificationCallback";
    }

    @PostConstruct
    public void validateCloudConfiguration() {
        if (!checkEnabled) {
            return;
        }
        if (env == null) {
            return;
        }
        String[] activeProfiles = env.getActiveProfiles();
        Set<String> activeSet = new HashSet<>();
        if (activeProfiles != null) {
            for (String ap : activeProfiles) {
                if (ap != null) activeSet.add(ap.trim().toLowerCase());
            }
        }
        boolean hasCloudProfile = activeSet.contains("prod") || activeSet.contains("production") || activeSet.contains("staging");
        if (hasCloudProfile) {
            String flywayUser;
            String flywayPass;
            try {
                flywayUser = env.getProperty("spring.flyway.user");
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "CRITICAL SECURITY CONFIGURATION FAILURE: Missing required explicit Flyway migration user (SPRING_FLYWAY_USER) for cloud profile! " + e.getMessage(), e);
            }
            try {
                flywayPass = env.getProperty("spring.flyway.password");
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "CRITICAL SECURITY CONFIGURATION FAILURE: Missing required explicit Flyway migration password (SPRING_FLYWAY_PASSWORD) for cloud profile! " + e.getMessage(), e);
            }

            if (flywayUser == null || flywayUser.trim().isEmpty() || flywayUser.startsWith("${")) {
                throw new IllegalStateException(
                        "CRITICAL SECURITY CONFIGURATION FAILURE: Missing required explicit Flyway migration user (SPRING_FLYWAY_USER) for cloud profile! Application fails closed.");
            }
            if (flywayPass == null || flywayPass.startsWith("${")) {
                throw new IllegalStateException(
                        "CRITICAL SECURITY CONFIGURATION FAILURE: Missing required explicit Flyway migration password (SPRING_FLYWAY_PASSWORD) for cloud profile! Application fails closed.");
            }
        }
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        validateCloudConfiguration();
        configuration.callbacks(this);
    }

    @Override
    public void handle(Event event, Context context) {
        if (!checkEnabled) {
            log.warn("Database isolation verification callback is explicitly disabled.");
            return;
        }

        Connection connection = context.getConnection();
        verifyIsolation(connection);
    }

    public void verifyIsolation(Connection connection) {
        if (connection == null) {
            throw new SecurityException("DATABASE ISOLATION VIOLATION: Database connection is null!");
        }

        // 1. Fail closed on missing, blank, or unrecognized environment type
        if (expectedEnvironmentType == null || expectedEnvironmentType.trim().isEmpty()) {
            throw new SecurityException(
                    "DATABASE ISOLATION VIOLATION: Missing required configuration 'app.datasource.expected-environment-type'! " +
                    "Base configuration fails closed. Expected one of: LOCAL, STAGING, PRODUCTION.");
        }

        String normalizedEnvType = expectedEnvironmentType.trim().toUpperCase();
        if (!ALLOWED_ENVIRONMENTS.contains(normalizedEnvType)) {
            throw new SecurityException(String.format(
                    "DATABASE ISOLATION VIOLATION: Unrecognized environment type '%s'! Allowed values: %s.",
                    expectedEnvironmentType, ALLOWED_ENVIRONMENTS));
        }

        // 2. Validate Profile and Environment Consistency
        validateProfileConsistency(normalizedEnvType);

        try {
            DatabaseMetaData metaData = connection.getMetaData();
            String url = metaData.getURL();
            String userName = metaData.getUserName();

            log.info("Executing Pre-Migration Database Isolation Check: URL={}, User={}, ExpectedEnv={}",
                    maskUrl(url), userName, normalizedEnvType);

            // Safe bypass for local development / test databases ONLY when explicitly LOCAL
            if ("LOCAL".equals(normalizedEnvType)) {
                log.info("LOCAL environment configured and verified under permitted profile. Bypassing cloud canary table verification.");
                return;
            }

            // 3. Cloud Environments MUST require a non-empty expected project reference
            if (expectedProjectRef == null || expectedProjectRef.trim().isEmpty()) {
                throw new SecurityException(String.format(
                        "DATABASE ISOLATION VIOLATION: Missing required configuration 'app.datasource.expected-project-ref' for cloud environment '%s'! " +
                        "Both STAGING and PRODUCTION must explicitly configure an expected Supabase project reference.",
                        normalizedEnvType));
            }

            String cleanExpectedRef = expectedProjectRef.trim();

            // 4. Connection Project Reference Extraction & Validation
            String extractedProjectRef = extractSupabaseProjectRef(url, userName);

            if (extractedProjectRef == null || !extractedProjectRef.equalsIgnoreCase(cleanExpectedRef)) {
                throw new SecurityException(String.format(
                        "DATABASE ISOLATION VIOLATION: Connected to project reference '%s', but expected '%s'!",
                        extractedProjectRef, cleanExpectedRef));
            }

            if (prohibitedProjectRefs != null && !prohibitedProjectRefs.trim().isEmpty()) {
                List<String> prohibited = Arrays.asList(prohibitedProjectRefs.split(","));
                for (String p : prohibited) {
                    String cleanP = p.trim();
                    if (!cleanP.isEmpty() && cleanP.equalsIgnoreCase(extractedProjectRef)) {
                        throw new SecurityException(String.format(
                                "CRITICAL DATABASE ISOLATION VIOLATION: Connected to PROHIBITED project reference '%s'!",
                                cleanP));
                    }
                }
            }

            // 5. Query Canary Table: _system_environment_canary
            queryAndValidateCanaryTable(connection, normalizedEnvType, cleanExpectedRef);

            log.info("Pre-Migration Database Isolation Check PASSED for environment '{}' (project_ref={}).",
                    normalizedEnvType, cleanExpectedRef);

        } catch (SQLException e) {
            throw new SecurityException("DATABASE ISOLATION VIOLATION: Failed to verify database environment: " + e.getMessage(), e);
        }
    }

    private void validateProfileConsistency(String normalizedEnvType) {
        if (env == null) {
            // Standalone test without Environment context
            return;
        }

        String[] activeProfiles = env.getActiveProfiles();
        boolean hasActiveProfiles = activeProfiles != null && activeProfiles.length > 0;
        Set<String> activeSet = new HashSet<>();
        if (hasActiveProfiles) {
            for (String ap : activeProfiles) {
                if (ap != null) activeSet.add(ap.trim().toLowerCase());
            }
        }

        boolean hasCloudProfile = activeSet.contains("prod") || activeSet.contains("production") || activeSet.contains("staging");
        boolean hasLocalProfile = activeSet.contains("local") || activeSet.contains("test") || activeSet.contains("dev");

        if ("LOCAL".equals(normalizedEnvType)) {
            if (hasCloudProfile) {
                throw new SecurityException(String.format(
                        "CRITICAL DATABASE ISOLATION VIOLATION: Environment type LOCAL is strictly prohibited when running under cloud profiles %s! " +
                        "Attempted to bypass canary table verification.", activeSet));
            }

            if (hasActiveProfiles && !hasLocalProfile) {
                throw new SecurityException(String.format(
                        "DATABASE ISOLATION VIOLATION: Environment type LOCAL is only permitted under explicitly activated 'local' or 'test' profiles! Active: %s",
                        activeSet));
            }

            if (!hasActiveProfiles) {
                // Check default profiles
                String[] defaultProfiles = env.getDefaultProfiles();
                boolean defaultHasLocal = false;
                if (defaultProfiles != null) {
                    for (String dp : defaultProfiles) {
                        if ("local".equalsIgnoreCase(dp) || "test".equalsIgnoreCase(dp) || "dev".equalsIgnoreCase(dp)) {
                            defaultHasLocal = true;
                            break;
                        }
                    }
                }
                if (!defaultHasLocal) {
                    throw new SecurityException(
                            "DATABASE ISOLATION VIOLATION: Environment type LOCAL configured without active or default 'local' profile!");
                }
            }
        } else if ("STAGING".equals(normalizedEnvType)) {
            if (activeSet.contains("prod") || activeSet.contains("production")) {
                throw new SecurityException(
                        "CRITICAL DATABASE ISOLATION VIOLATION: Environment type STAGING configured but production profile is active!");
            }
        } else if ("PRODUCTION".equals(normalizedEnvType)) {
            if (activeSet.contains("staging") || activeSet.contains("local")) {
                throw new SecurityException(String.format(
                        "CRITICAL DATABASE ISOLATION VIOLATION: Environment type PRODUCTION configured but conflicting profiles active: %s",
                        activeSet));
            }
        }
    }

    private void queryAndValidateCanaryTable(Connection connection, String expectedEnv, String expectedRef) throws SQLException {
        // Enforce that canary table contains exactly one singleton record
        String canarySql = "SELECT id, environment_name, supabase_project_ref FROM _system_environment_canary";

        try (PreparedStatement stmt = connection.prepareStatement(canarySql);
             ResultSet rs = stmt.executeQuery()) {

            if (!rs.next()) {
                throw new SecurityException(
                        "DATABASE ISOLATION VIOLATION: Canary table '_system_environment_canary' exists but contains no records! Controlled bootstrap required.");
            }

            int id = rs.getInt("id");
            String canaryEnv = rs.getString("environment_name");
            String canaryRef = rs.getString("supabase_project_ref");

            if (rs.next()) {
                throw new SecurityException(
                        "DATABASE ISOLATION VIOLATION: Canary table contains multiple conflicting records! Exactly one singleton canary record is permitted.");
            }

            if (id != 1) {
                throw new SecurityException(
                        "DATABASE ISOLATION VIOLATION: Canary record has invalid id=" + id + ". Singleton record must have id=1.");
            }

            if (canaryEnv == null || canaryEnv.trim().isEmpty()) {
                throw new SecurityException("DATABASE ISOLATION VIOLATION: Canary table contains blank environment_name!");
            }

            String cleanCanaryEnv = canaryEnv.trim().toUpperCase();

            // Strict environment comparison
            if (!cleanCanaryEnv.equals(expectedEnv)) {
                throw new SecurityException(String.format(
                        "CRITICAL DATABASE ISOLATION VIOLATION: Canary environment '%s' does not match expected environment '%s'!",
                        cleanCanaryEnv, expectedEnv));
            }

            // Cross-environment safety guards
            if ("STAGING".equals(expectedEnv) && "PRODUCTION".equals(cleanCanaryEnv)) {
                throw new SecurityException(
                        "FATAL DATABASE ISOLATION VIOLATION: Staging runtime attempted connection to a database tagged as PRODUCTION!");
            }
            if ("PRODUCTION".equals(expectedEnv) && "STAGING".equals(cleanCanaryEnv)) {
                throw new SecurityException(
                        "FATAL DATABASE ISOLATION VIOLATION: Production runtime attempted connection to a database tagged as STAGING!");
            }

            if (canaryRef == null || !canaryRef.trim().equalsIgnoreCase(expectedRef)) {
                throw new SecurityException(String.format(
                        "DATABASE ISOLATION VIOLATION: Canary project_ref '%s' does not match expected '%s'!",
                        canaryRef, expectedRef));
            }

        } catch (SQLException e) {
            // Check if table missing (PostgreSQL 42P01 or H2 table not found)
            if (e.getMessage() != null && (e.getMessage().contains("_system_environment_canary") || "42P01".equals(e.getSQLState()))) {
                throw new SecurityException(
                        "DATABASE ISOLATION VIOLATION: Canary table '_system_environment_canary' does not exist! Controlled bootstrap required before running migrations.", e);
            }
            throw e;
        }
    }

    public static String extractSupabaseProjectRef(String url, String userName) {
        if (userName != null) {
            Matcher userMatcher = SUPABASE_USER_REF_PATTERN.matcher(userName);
            if (userMatcher.find()) {
                return userMatcher.group(1);
            }
        }
        if (url != null) {
            Matcher urlMatcher = SUPABASE_URL_REF_PATTERN.matcher(url);
            if (urlMatcher.find()) {
                return urlMatcher.group(1);
            }
            // Check if user credentials embedded in JDBC URL
            Matcher urlUserMatcher = SUPABASE_USER_REF_PATTERN.matcher(url);
            if (urlUserMatcher.find()) {
                return urlUserMatcher.group(1);
            }
        }
        return null;
    }

    private String maskUrl(String url) {
        if (url == null) return "null";
        return url.replaceAll(":[^:@/]+@", ":***@");
    }

    // Setters for unit testing without full Spring context
    public void setCheckEnabled(boolean checkEnabled) {
        this.checkEnabled = checkEnabled;
    }

    public void setExpectedEnvironmentType(String expectedEnvironmentType) {
        this.expectedEnvironmentType = expectedEnvironmentType;
    }

    public void setExpectedProjectRef(String expectedProjectRef) {
        this.expectedProjectRef = expectedProjectRef;
    }

    public void setProhibitedProjectRefs(String prohibitedProjectRefs) {
        this.prohibitedProjectRefs = prohibitedProjectRefs;
    }
}
