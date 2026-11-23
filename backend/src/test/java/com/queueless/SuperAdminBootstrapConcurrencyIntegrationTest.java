package com.queueless;

import com.queueless.entity.AuditLog;
import com.queueless.entity.User;
import com.queueless.entity.UserRole;
import com.queueless.repository.AuditLogRepository;
import com.queueless.repository.UserRepository;
import com.queueless.service.SuperAdminBootstrapService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Super Admin Bootstrap Concurrency & Resilience Integration Test.
 * Validates that when two independent application instances start up simultaneously
 * against the exact same empty PostgreSQL database:
 * 1. Exactly one Super Admin account is created.
 * 2. Zero duplicate emails or phone numbers exist.
 * 3. Both application instances reach healthy startup without failing or crashing.
 * 4. PostgreSQL unique constraint collisions are caught and handled safely.
 * 5. Existing Super Admin credentials are NOT unintentionally overwritten on restart.
 * 6. Repeated application restarts do not create additional Super Admin accounts.
 */
public class SuperAdminBootstrapConcurrencyIntegrationTest {

    private static EmbeddedPostgres embeddedPostgres;
    private static String jdbcUrl;

    @BeforeAll
    static void setUpDatabase() throws IOException {
        embeddedPostgres = EmbeddedPostgres.builder().start();
        jdbcUrl = embeddedPostgres.getJdbcUrl("postgres", "postgres");
    }

    @AfterAll
    static void tearDownDatabase() throws IOException {
        if (embeddedPostgres != null) {
            embeddedPostgres.close();
        }
    }

    private String[] buildAppArgs() {
        return new String[] {
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=postgres",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.flyway.enabled=true",
                "--spring.flyway.baseline-on-migrate=true",
                "--app.superadmin.bootstrap.enabled=true",
                "--app.superadmin.bootstrap.email=concurrent-admin@queueless.in",
                "--app.superadmin.bootstrap.phone=+919999900000",
                "--app.superadmin.bootstrap.password=SuperAdminInitialPass123!",
                "--server.port=0",
                "--management.endpoints.web.exposure.include=health",
                "--spring.main.banner-mode=off",
                "--spring.profiles.active=local",
                "--app.datasource.expected-environment-type=LOCAL",
                "--org.springframework.boot.logging.LoggingSystem=none"
        };
    }

    @Test
    @DisplayName("Concurrent multi-instance startup against empty PostgreSQL creates exactly one Super Admin safely")
    void testConcurrentBootstrapAgainstEmptyDatabase() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch finishSignal = new CountDownLatch(2);

        AtomicReference<ConfigurableApplicationContext> ctx1Ref = new AtomicReference<>();
        AtomicReference<ConfigurableApplicationContext> ctx2Ref = new AtomicReference<>();
        AtomicReference<Throwable> err1Ref = new AtomicReference<>();
        AtomicReference<Throwable> err2Ref = new AtomicReference<>();

        executor.submit(() -> {
            try {
                startSignal.await();
                ConfigurableApplicationContext ctx = new SpringApplicationBuilder(QueueLessApplication.class)
                        .run(buildAppArgs());
                ctx1Ref.set(ctx);
            } catch (Throwable t) {
                err1Ref.set(t);
            } finally {
                finishSignal.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startSignal.await();
                ConfigurableApplicationContext ctx = new SpringApplicationBuilder(QueueLessApplication.class)
                        .run(buildAppArgs());
                ctx2Ref.set(ctx);
            } catch (Throwable t) {
                err2Ref.set(t);
            } finally {
                finishSignal.countDown();
            }
        });

        // Trigger simultaneous startup
        startSignal.countDown();

        boolean completed = finishSignal.await(120, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "Both application instances should complete startup within timeout");
        assertNull(err1Ref.get(), "Instance 1 failed to start: " + (err1Ref.get() != null ? err1Ref.get().getMessage() : ""));
        assertNull(err2Ref.get(), "Instance 2 failed to start: " + (err2Ref.get() != null ? err2Ref.get().getMessage() : ""));

        ConfigurableApplicationContext ctx1 = ctx1Ref.get();
        ConfigurableApplicationContext ctx2 = ctx2Ref.get();

        assertNotNull(ctx1, "Instance 1 context must be initialized");
        assertNotNull(ctx2, "Instance 2 context must be initialized");
        assertTrue(ctx1.isRunning(), "Instance 1 must be healthy and running");
        assertTrue(ctx2.isRunning(), "Instance 2 must be healthy and running");

        try {
            UserRepository userRepo = ctx1.getBean(UserRepository.class);
            AuditLogRepository auditRepo = ctx1.getBean(AuditLogRepository.class);
            PasswordEncoder passwordEncoder = ctx1.getBean(PasswordEncoder.class);

            // 1. Verify exactly one Super Admin was created
            long superAdminCount = userRepo.countByRole(UserRole.ROLE_SUPER_ADMIN);
            assertEquals(1L, superAdminCount, "Exactly one Super Admin account must be created across concurrent instances");

            // 2. Verify no duplicate emails or phone numbers
            List<User> adminsByEmail = userRepo.findAll().stream()
                    .filter(u -> "concurrent-admin@queueless.in".equalsIgnoreCase(u.getEmail()))
                    .toList();
            assertEquals(1, adminsByEmail.size(), "Only one account with email concurrent-admin@queueless.in should exist");

            List<User> adminsByPhone = userRepo.findAll().stream()
                    .filter(u -> "+919999900000".equals(u.getPhone()))
                    .toList();
            assertEquals(1, adminsByPhone.size(), "Only one account with phone +919999900000 should exist");

            User superAdmin = adminsByEmail.get(0);
            assertEquals(UserRole.ROLE_SUPER_ADMIN, superAdmin.getRole());
            assertEquals("ACTIVE", superAdmin.getStatus());
            assertTrue(passwordEncoder.matches("SuperAdminInitialPass123!", superAdmin.getPassword()),
                    "Super Admin initial password must match configured value");

            // 3. Verify audit log was recorded
            List<AuditLog> auditLogs = auditRepo.findAll().stream()
                    .filter(a -> "SUPER_ADMIN_BOOTSTRAP".equals(a.getAction()))
                    .toList();
            assertFalse(auditLogs.isEmpty(), "Audit log for SUPER_ADMIN_BOOTSTRAP must be recorded");

            // 4. Verify existing credentials are NOT unintentionally overwritten
            // Simulate an administrator changing their password
            String customChangedPassword = "UserChangedPassword456$#@!";
            superAdmin.setPassword(passwordEncoder.encode(customChangedPassword));
            superAdmin.setCredentialsUpdatedAt(Instant.now());
            userRepo.saveAndFlush(superAdmin);

            // Run SuperAdminBootstrapService again on both instances (simulating restart or redeploy)
            SuperAdminBootstrapService bootstrapServiceInstance1 = ctx1.getBean(SuperAdminBootstrapService.class);
            SuperAdminBootstrapService bootstrapServiceInstance2 = ctx2.getBean(SuperAdminBootstrapService.class);

            assertDoesNotThrow(() -> bootstrapServiceInstance1.run(),
                    "Repeated bootstrap execution on Instance 1 must succeed without exception");
            assertDoesNotThrow(() -> bootstrapServiceInstance2.run(),
                    "Repeated bootstrap execution on Instance 2 must succeed without exception");

            // Verify password was NOT overwritten back to bootstrap password
            User reloadedAdmin = userRepo.findById(superAdmin.getId()).orElseThrow();
            assertTrue(passwordEncoder.matches(customChangedPassword, reloadedAdmin.getPassword()),
                    "Super Admin changed credentials must NOT be unintentionally overwritten by bootstrap service");
            assertFalse(passwordEncoder.matches("SuperAdminInitialPass123!", reloadedAdmin.getPassword()),
                    "Super Admin credentials must remain the user-updated password");

            // Verify count is still exactly 1
            assertEquals(1L, userRepo.countByRole(UserRole.ROLE_SUPER_ADMIN),
                    "Super Admin count must remain exactly 1 after repeated bootstrap execution");

            // 5. Test clean application restart simulation
            ctx2.close();

            ConfigurableApplicationContext ctx3 = new SpringApplicationBuilder(QueueLessApplication.class)
                    .run(buildAppArgs());
            try {
                assertTrue(ctx3.isRunning(), "Restarted Instance 3 must reach healthy running state");
                UserRepository userRepo3 = ctx3.getBean(UserRepository.class);
                assertEquals(1L, userRepo3.countByRole(UserRole.ROLE_SUPER_ADMIN),
                        "Restarted instance must observe exactly one Super Admin account");
            } finally {
                ctx3.close();
            }

        } finally {
            ctx1.close();
            if (ctx2.isRunning()) {
                ctx2.close();
            }
        }
    }
}
