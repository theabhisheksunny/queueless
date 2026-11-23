package com.queueless;

import com.queueless.service.DataLifecycleMaintenanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.profiles.active=prod",
        "PORT=10000",
        "queueless.cleanup.on-startup=true",
        "spring.datasource.url=jdbc:h2:mem:queueless_render_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.user=sa",
        "spring.flyway.password=",
        "app.datasource.isolation-check.enabled=false",
        "app.jwt.secret=test-render-jwt-secret-key-for-unit-testing-32bytes!",
        "app.admin.key=test-render-admin-key-2026",
        "app.cors.allowed-origins=https://queueless.theabhisheksunny.workers.dev"
})
@ActiveProfiles("prod")
@DisplayName("Render Free Deployment Configuration & Runtime Verification Tests")
class RenderDeploymentConfigurationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataLifecycleMaintenanceService maintenanceService;

    @Value("${server.port}")
    private String configuredPort;

    @Value("${queueless.cleanup.on-startup}")
    private boolean cleanupOnStartup;

    @Value("${app.cors.allowed-origins}")
    private String allowedCorsOrigins;

    @Test
    @DisplayName("Render Environment: Server Port binds dynamically to PORT=10000")
    void testDynamicPortBindingForRender() {
        assertEquals("10000", configuredPort, "Server port must dynamically resolve PORT environment variable provided by Render");
        assertEquals("10000", environment.getProperty("server.port"));
    }

    @Test
    @DisplayName("Render Environment: Startup cleanup catch-up toggle is enabled and observable")
    void testStartupCleanupCatchUpToggle() {
        assertTrue(cleanupOnStartup, "Startup cleanup catch-up must be enabled via queueless.cleanup.on-startup");
        assertTrue(maintenanceService.isCleanupOnStartup(), "Service must report cleanupOnStartup as true");
        assertDoesNotThrow(() -> maintenanceService.onApplicationReady(),
                "onApplicationReady() must execute asynchronously without throwing exception or blocking startup");
    }

    @Test
    @DisplayName("Render Environment: CORS allowed origins includes Cloudflare Workers frontend domain")
    void testCorsAllowedOriginsForCloudflareWorker() {
        assertNotNull(allowedCorsOrigins);
        assertTrue(allowedCorsOrigins.contains("https://queueless.theabhisheksunny.workers.dev"),
                "Production CORS origins must allow Cloudflare Worker frontend domain");
    }

    @Test
    @DisplayName("Render Environment: Conservative Hikari connection pool limits are enforced")
    void testConservativeConnectionPoolSettings() {
        String maxPoolSize = environment.getProperty("spring.datasource.hikari.maximum-pool-size");
        assertNotNull(maxPoolSize);
        int maxPool = Integer.parseInt(maxPoolSize);
        assertTrue(maxPool <= 10, "Render HikariCP pool must be conservative (<= 10) to respect Supabase pooler quotas");
    }

    @Test
    @DisplayName("Render Environment: Super Admin bootstrap is disabled by default in production profile")
    void testSuperAdminBootstrapDisabledInProduction() {
        String bootstrapEnabled = environment.getProperty("app.superadmin.bootstrap.enabled");
        assertEquals("false", bootstrapEnabled,
                "Super Admin auto-bootstrap must default to disabled in production profile");
    }
}
