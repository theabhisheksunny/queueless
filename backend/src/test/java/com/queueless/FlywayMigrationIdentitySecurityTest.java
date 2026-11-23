package com.queueless;

import com.queueless.config.DatabaseIsolationVerificationCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Flyway Migration Identity Fail-Closed Security Test")
class FlywayMigrationIdentitySecurityTest {

    @Test
    @DisplayName("Staging Profile: Callback must FAIL CLOSED if spring.flyway.user is missing")
    void testStagingFailsClosedWithoutFlywayUser() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("staging");
        // spring.flyway.user is NOT provided
        env.setProperty("spring.flyway.password", "secret-migrator-pass");

        DatabaseIsolationVerificationCallback callback = new DatabaseIsolationVerificationCallback(env);
        callback.setCheckEnabled(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, callback::validateCloudConfiguration,
                "Callback must fail closed when spring.flyway.user is missing in staging profile");
        assertTrue(ex.getMessage().contains("SPRING_FLYWAY_USER"),
                "Exception message must mention SPRING_FLYWAY_USER: " + ex.getMessage());
    }

    @Test
    @DisplayName("Staging Profile: Callback must FAIL CLOSED if spring.flyway.user is unresolvable placeholder")
    void testStagingFailsClosedWithUnresolvedPlaceholder() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("staging");
        env.setProperty("spring.flyway.user", "${SPRING_FLYWAY_USER}");
        env.setProperty("spring.flyway.password", "${SPRING_FLYWAY_PASSWORD}");

        DatabaseIsolationVerificationCallback callback = new DatabaseIsolationVerificationCallback(env);
        callback.setCheckEnabled(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, callback::validateCloudConfiguration,
                "Callback must fail closed when spring.flyway.user is an unresolved placeholder");
        assertTrue(ex.getMessage().contains("SPRING_FLYWAY_USER"),
                "Exception message must mention SPRING_FLYWAY_USER: " + ex.getMessage());
    }

    @Test
    @DisplayName("Production Profile: Callback must FAIL CLOSED if spring.flyway.user is missing")
    void testProductionFailsClosedWithoutFlywayUser() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        // spring.flyway.user is NOT provided
        env.setProperty("spring.flyway.password", "secret-migrator-pass");

        DatabaseIsolationVerificationCallback callback = new DatabaseIsolationVerificationCallback(env);
        callback.setCheckEnabled(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, callback::validateCloudConfiguration,
                "Callback must fail closed when spring.flyway.user is missing in prod profile");
        assertTrue(ex.getMessage().contains("SPRING_FLYWAY_USER"),
                "Exception message must mention SPRING_FLYWAY_USER: " + ex.getMessage());
    }

    @Test
    @DisplayName("Production Profile: Callback must FAIL CLOSED if spring.flyway.password is missing")
    void testProductionFailsClosedWithoutFlywayPassword() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("spring.flyway.user", "queueless_migrator_prod");
        // spring.flyway.password is NOT provided

        DatabaseIsolationVerificationCallback callback = new DatabaseIsolationVerificationCallback(env);
        callback.setCheckEnabled(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, callback::validateCloudConfiguration,
                "Callback must fail closed when spring.flyway.password is missing in prod profile");
        assertTrue(ex.getMessage().contains("SPRING_FLYWAY_PASSWORD"),
                "Exception message must mention SPRING_FLYWAY_PASSWORD: " + ex.getMessage());
    }

    @Test
    @DisplayName("Cloud Profile: Callback SUCCEEDS when both Flyway credentials are validly provided")
    void testCloudProfileSucceedsWithValidCredentials() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("spring.flyway.user", "queueless_migrator_prod");
        env.setProperty("spring.flyway.password", "strong-migrator-password-2026");

        DatabaseIsolationVerificationCallback callback = new DatabaseIsolationVerificationCallback(env);
        callback.setCheckEnabled(true);

        assertDoesNotThrow(callback::validateCloudConfiguration,
                "Callback must succeed when valid Flyway credentials are provided for cloud profile");
    }
}
