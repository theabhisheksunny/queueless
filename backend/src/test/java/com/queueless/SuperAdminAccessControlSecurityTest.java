package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.AdminSettingRequest;
import com.queueless.entity.*;
import com.queueless.repository.*;
import com.queueless.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class SuperAdminAccessControlSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private PaymentRecordRepository paymentRecordRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionSeatRepository seatRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${app.admin.key:admin-secret-queueless-2026}")
    private String adminKey;

    private User testSuperAdmin;
    private User testOwner;
    private User testStaff;
    private Shop testShop;

    private String superAdminToken;
    private String ownerToken;
    private String staffToken;

    @BeforeEach
    void setUp() {
        String ts = String.valueOf(System.currentTimeMillis());

        // 1. Create Super Admin User
        testSuperAdmin = new User();
        testSuperAdmin.setEmail("superadmin_" + ts + "@queueless.in");
        testSuperAdmin.setPhone("+919999" + ts.substring(Math.max(0, ts.length() - 6)));
        testSuperAdmin.setFullName("Platform Super Admin");
        testSuperAdmin.setRole(UserRole.ROLE_SUPER_ADMIN);
        testSuperAdmin.setPassword(passwordEncoder.encode("SecureSuperPass!123"));
        testSuperAdmin.setStatus("ACTIVE");
        testSuperAdmin.setCredentialsUpdatedAt(Instant.now());
        testSuperAdmin.setActiveSessionId(UUID.randomUUID().toString());
        testSuperAdmin = userRepository.save(testSuperAdmin);
        superAdminToken = tokenProvider.generateTokenForUsername(testSuperAdmin.getEmail(), testSuperAdmin.getActiveSessionId());

        // 2. Create Shop Owner User & Shop
        testOwner = new User();
        testOwner.setEmail("owner_" + ts + "@queueless.in");
        testOwner.setPhone("+919811" + ts.substring(Math.max(0, ts.length() - 6)));
        testOwner.setFullName("Shop Owner Ramesh");
        testOwner.setRole(UserRole.ROLE_OWNER);
        testOwner.setPassword(passwordEncoder.encode("123456"));
        testOwner.setStatus("ACTIVE");
        testOwner.setCredentialsUpdatedAt(Instant.now());
        testOwner.setActiveSessionId(UUID.randomUUID().toString());
        testOwner = userRepository.save(testOwner);
        ownerToken = tokenProvider.generateTokenForUsername(testOwner.getPhone(), testOwner.getActiveSessionId());

        testShop = new Shop();
        testShop.setOwner(testOwner);
        testShop.setName("Ramesh Salon " + ts);
        testShop.setCategory("Salon & Spa");
        testShop.setAvgServiceDurationMinutes(15);
        testShop.setIsPrimary(true);
        testShop = shopRepository.save(testShop);

        // 3. Create Staff User
        testStaff = new User();
        testStaff.setEmail("staff_" + ts + "@queueless.in");
        testStaff.setPhone("+919711" + ts.substring(Math.max(0, ts.length() - 6)));
        testStaff.setFullName("Staff Vikram");
        testStaff.setRole(UserRole.ROLE_STAFF);
        testStaff.setOwner(testOwner);
        testStaff.setPassword(passwordEncoder.encode("123456"));
        testStaff.setStatus("ACTIVE");
        testStaff.setCredentialsUpdatedAt(Instant.now());
        testStaff.setActiveSessionId(UUID.randomUUID().toString());
        testStaff = userRepository.save(testStaff);
        staffToken = tokenProvider.generateTokenForUsername(testStaff.getPhone(), testStaff.getActiveSessionId());
    }

    @Test
    @DisplayName("1. Direct unauthenticated API access to Super Admin endpoints returns 403 Forbidden")
    void unauthenticatedAccessToSuperAdminApis_Returns403() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/summary"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));

        mockMvc.perform(get("/api/admin/subscriptions"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/users-and-shops"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/payments"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/activity-logs"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/payments/99999/approve"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/payments/99999/reject"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/users/99999/suspend"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyPrice\":51,\"yearlyPrice\":501}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("2. Shop Owner accounts receive HTTP 403 Forbidden when attempting to access Super Admin APIs")
    void shopOwnerAttemptingSuperAdminAccess_Returns403() throws Exception {
        // Authenticated Owner JWT attempts Super Admin access
        mockMvc.perform(get("/api/admin/dashboard/summary")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));

        mockMvc.perform(get("/api/admin/payments")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/subscriptions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/users-and-shops")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/payments/1/approve")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/users/" + testStaff.getId() + "/suspend")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("3. Staff accounts receive HTTP 403 Forbidden when attempting to access Super Admin APIs")
    void staffAttemptingSuperAdminAccess_Returns403() throws Exception {
        // Authenticated Staff JWT attempts Super Admin access
        mockMvc.perform(get("/api/admin/dashboard/summary")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(get("/api/admin/subscriptions")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/payments/1/approve")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/users/" + testOwner.getId() + "/suspend")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("4. Authenticated Super Admin JWT successfully accesses all Super Admin APIs")
    void superAdminWithJwt_AccessGranted() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalShopOwners").exists())
                .andExpect(jsonPath("$.totalRegisteredBranches").exists());

        mockMvc.perform(get("/api/admin/subscriptions")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/users-and-shops")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/payments")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/activity-logs")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("5. Server-to-server operational access via X-Admin-Key succeeds, but sensitive Super Admin endpoints strictly return 403")
    void serverToServerAccess_ValidKeySucceeds_InvalidKeyFails() throws Exception {
        // Valid Admin Key succeeds for operational endpoints (ROLE_ADMIN service identity)
        mockMvc.perform(get("/api/admin/config")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyPrice").exists());

        // Invalid Admin Key returns 403
        mockMvc.perform(get("/api/admin/config")
                        .header("X-Admin-Key", "forged-or-incorrect-admin-key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // X-Admin-Key is strictly prohibited from sensitive Super Admin endpoints (dashboard, payments)
        mockMvc.perform(get("/api/admin/dashboard/summary")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/admin/payments/99999/approve")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("6. Public registration never creates SUPER_ADMIN; role parameter injection is strictly ignored")
    void publicRegistration_CannotElevateToSuperAdmin() throws Exception {
        String phone = "91" + String.format("%08d", (int)(System.currentTimeMillis() % 100000000));

        Map<String, Object> regPayload = new HashMap<>();
        regPayload.put("fullName", "Malicious Attacker");
        regPayload.put("phone", phone);
        regPayload.put("pin", "654321");
        regPayload.put("confirmPin", "654321");
        regPayload.put("shopName", "Attacker Store");
        regPayload.put("role", "ROLE_SUPER_ADMIN"); // Injection attempt
        regPayload.put("roleName", "SUPER_ADMIN");   // Alternative injection attempt

        String responseContent = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regPayload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Verify response role is strictly ROLE_OWNER
        assertTrue(responseContent.contains("ROLE_OWNER"));
        assertFalse(responseContent.contains("ROLE_SUPER_ADMIN"));

        // Verify in database
        User createdUser = userRepository.findByIdentifier("+91" + phone.substring(phone.length() - 10))
                .or(() -> userRepository.findByIdentifier(phone))
                .orElseThrow();
        assertEquals(UserRole.ROLE_OWNER, createdUser.getRole());

        // Verify the newly registered user cannot access super admin endpoints
        String userToken = tokenProvider.generateTokenForUsername(createdUser.getPhone(), createdUser.getActiveSessionId());
        mockMvc.perform(get("/api/admin/dashboard/summary")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("7. Super Admin cannot impersonate shop owners or directly operate merchant endpoints (Requirement 8)")
    void superAdmin_CannotImpersonateOrAccessOwnerEndpoints() throws Exception {
        // Super Admin attempts to access owner staff endpoint
        mockMvc.perform(get("/api/owner/staff")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isForbidden());

        // Super Admin attempts to access owner settings
        mockMvc.perform(get("/api/owner/settings")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isForbidden());

        // Super Admin attempts to resolve another owner's shop on queue endpoint
        mockMvc.perform(post("/api/queue/start?shopId=" + testShop.getId())
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("8. Sensitive administrative actions record immutable append-only audit records")
    void sensitiveAdministrativeActions_RecordAuditLogs() throws Exception {
        long initialLogsCount = auditLogRepository.count();

        // 1. Approve a payment
        PaymentRecord payment = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                "UTR_AUDIT_" + System.currentTimeMillis(),
                "payer@upi"
        );
        payment.setStatus("PENDING_APPROVAL");
        payment = paymentRecordRepository.save(payment);

        mockMvc.perform(post("/api/admin/payments/" + payment.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        // 2. Reject another payment
        PaymentRecord payment2 = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                "UTR_REJECT_" + System.currentTimeMillis(),
                "payer@upi"
        );
        payment2.setStatus("PENDING_APPROVAL");
        payment2 = paymentRecordRepository.save(payment2);

        mockMvc.perform(post("/api/admin/payments/" + payment2.getId() + "/reject")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"UTR invalid or amount mismatch\"}"))
                .andExpect(status().isOk());

        // 3. Update Settings
        AdminSettingRequest settingRequest = new AdminSettingRequest();
        settingRequest.setUpiId("admin@upi");
        settingRequest.setUpiName("QueueLess Admin");
        settingRequest.setMonthlyPrice(BigDecimal.valueOf(51));
        settingRequest.setYearlyPrice(BigDecimal.valueOf(501));

        mockMvc.perform(post("/api/admin/settings")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settingRequest)))
                .andExpect(status().isOk());

        // 4. Suspend user
        mockMvc.perform(post("/api/admin/users/" + testStaff.getId() + "/suspend")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Administrative suspension for policy review\"}"))
                .andExpect(status().isOk());

        // Verify audit logs were appended
        long finalLogsCount = auditLogRepository.count();
        assertTrue(finalLogsCount >= initialLogsCount + 4, "Expected at least 4 new audit log entries");

        List<AuditLog> recentLogs = auditLogRepository.findTop50ByOrderByCreatedAtDesc();
        boolean hasApprove = recentLogs.stream().anyMatch(l -> "APPROVE_PAYMENT".equals(l.getAction()));
        boolean hasReject = recentLogs.stream().anyMatch(l -> "REJECT_PAYMENT".equals(l.getAction()));
        boolean hasSettings = recentLogs.stream().anyMatch(l -> "ADMIN_SETTINGS_UPDATE".equals(l.getAction()));
        boolean hasSuspend = recentLogs.stream().anyMatch(l -> "ADMIN_ACCOUNT_SUSPENSION".equals(l.getAction()));

        assertTrue(hasApprove, "Expected APPROVE_PAYMENT in audit logs");
        assertTrue(hasReject, "Expected REJECT_PAYMENT in audit logs");
        assertTrue(hasSettings, "Expected ADMIN_SETTINGS_UPDATE in audit logs");
        assertTrue(hasSuspend, "Expected ADMIN_ACCOUNT_SUSPENSION in audit logs");
    }

    @Test
    @DisplayName("9. Account suspension revokes active session, invalidates token and blocks login")
    void accountSuspension_RevokesSessionAndBlocksLogin() throws Exception {
        // Suspend the owner account
        mockMvc.perform(post("/api/admin/users/" + testOwner.getId() + "/suspend")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Terms violation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User account suspended successfully."));

        // Verify owner's existing token is immediately rejected
        mockMvc.perform(get("/api/owner/staff")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isUnauthorized());

        // Verify owner login is rejected
        mockMvc.perform(post("/api/auth/pin-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"phone\":\"%s\",\"pin\":\"123456\"}", testOwner.getPhone())))
                .andExpect(status().isUnauthorized());

        // Reactivate account
        mockMvc.perform(post("/api/admin/users/" + testOwner.getId() + "/reactivate")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User account reactivated successfully."));

        // Verify owner can log in again
        mockMvc.perform(post("/api/auth/pin-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"phone\":\"%s\",\"pin\":\"123456\"}", testOwner.getPhone())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("10. Super Admin accounts cannot be suspended by user suspension API")
    void superAdminAccount_CannotBeSuspended() throws Exception {
        mockMvc.perform(post("/api/admin/users/" + testSuperAdmin.getId() + "/suspend")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Attempt to suspend admin\"}"))
                .andExpect(status().isConflict()); // IllegalStateException mapped to 409 Conflict

        User adminInDb = userRepository.findById(testSuperAdmin.getId()).orElseThrow();
        assertEquals("ACTIVE", adminInDb.getStatus());
    }

    @Test
    @DisplayName("11. Concurrent administrative payment approval requests handle safely without conflict")
    void concurrentPaymentApproval_ExecutesSafely() throws Exception {
        PaymentRecord payment = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                "CONCURRENT_UTR_" + System.currentTimeMillis(),
                "concurrent@upi"
        );
        payment.setStatus("PENDING_APPROVAL");
        payment = paymentRecordRepository.save(payment);

        final Long paymentId = payment.getId();
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                try {
                    mockMvc.perform(post("/api/admin/payments/" + paymentId + "/approve")
                                    .header("Authorization", "Bearer " + superAdminToken))
                            .andExpect(status().isOk());
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errorCount.incrementAndGet();
                }
                return null;
            }));
        }

        latch.countDown();
        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // All threads should receive HTTP 200 (either the original approval or idempotent response)
        assertEquals(threadCount, successCount.get());
        assertEquals(0, errorCount.get());

        // Verify database state is APPROVED
        PaymentRecord recordAfter = paymentRecordRepository.findById(paymentId).orElseThrow();
        assertEquals("APPROVED", recordAfter.getStatus());
    }
}
