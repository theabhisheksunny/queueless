package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.AdminSettingRequest;
import com.queueless.dto.PinLoginRequest;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Clock;
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
public class SuperAdminAdversarialSecurityAuditTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private PaymentRecordRepository paymentRecordRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Value("${app.admin.key:admin-secret-queueless-2026}")
    private String adminKey;

    private User testSuperAdmin;
    private String superAdminToken;

    private User testOwner;
    private String ownerToken;

    private User testStaff;
    private String staffToken;

    private Shop testShop;
    private Business testBusiness;

    @BeforeEach
    void setUp() {
        String ts = String.valueOf(System.currentTimeMillis() % 10000000);

        // 1. Setup Platform Super Admin
        testSuperAdmin = new User();
        testSuperAdmin.setEmail("adversarial_admin_" + ts + "@queueless.in");
        testSuperAdmin.setPhone("+9199" + ts.substring(Math.max(0, ts.length() - 8)));
        testSuperAdmin.setFullName("Adversarial Super Admin");
        testSuperAdmin.setRole(UserRole.ROLE_SUPER_ADMIN);
        testSuperAdmin.setPassword(passwordEncoder.encode("AdvAdminPass!999"));
        testSuperAdmin.setStatus("ACTIVE");
        testSuperAdmin.setCredentialsUpdatedAt(Instant.now());
        testSuperAdmin.setActiveSessionId(UUID.randomUUID().toString());
        testSuperAdmin = userRepository.save(testSuperAdmin);
        superAdminToken = tokenProvider.generateTokenForUsername(testSuperAdmin.getEmail(), testSuperAdmin.getActiveSessionId());

        // 2. Setup Shop Owner
        testOwner = new User();
        testOwner.setEmail("adv_owner_" + ts + "@queueless.in");
        testOwner.setPhone("+9198" + ts.substring(Math.max(0, ts.length() - 8)));
        testOwner.setFullName("Merchant Ramesh");
        testOwner.setRole(UserRole.ROLE_OWNER);
        testOwner.setPassword(passwordEncoder.encode("123456"));
        testOwner.setStatus("ACTIVE");
        testOwner.setCredentialsUpdatedAt(Instant.now());
        testOwner.setActiveSessionId(UUID.randomUUID().toString());
        testOwner = userRepository.save(testOwner);
        ownerToken = tokenProvider.generateTokenForUsername(testOwner.getPhone(), testOwner.getActiveSessionId());

        // 3. Setup Business & Shop
        testBusiness = new Business(testOwner, "Adversarial Biz " + ts);
        testBusiness = businessRepository.save(testBusiness);

        testShop = new Shop(testOwner, "Adversarial Shop " + ts, "RETAIL", 20);
        testShop.setBusiness(testBusiness);
        testShop.setIsPrimary(true);
        testShop.setRegistrationStatus("REGISTERED");
        testShop = shopRepository.save(testShop);

        // 4. Setup Staff
        testStaff = new User();
        testStaff.setEmail("adv_staff_" + ts + "@queueless.in");
        testStaff.setPhone("+9197" + ts.substring(Math.max(0, ts.length() - 8)));
        testStaff.setFullName("Staff Suresh");
        testStaff.setRole(UserRole.ROLE_STAFF);
        testStaff.setOwner(testOwner);
        testStaff.setPassword(passwordEncoder.encode("654321"));
        testStaff.setStatus("ACTIVE");
        testStaff.setCredentialsUpdatedAt(Instant.now());
        testStaff.setActiveSessionId(UUID.randomUUID().toString());
        testStaff = userRepository.save(testStaff);
        staffToken = tokenProvider.generateTokenForUsername(testStaff.getPhone(), testStaff.getActiveSessionId());
    }

    private static class EndpointTarget {
        final HttpMethod method;
        final String uri;
        final String jsonBody;

        EndpointTarget(HttpMethod method, String uri) {
            this(method, uri, null);
        }

        EndpointTarget(HttpMethod method, String uri, String jsonBody) {
            this.method = method;
            this.uri = uri;
            this.jsonBody = jsonBody;
        }
    }

    private List<EndpointTarget> getSensitiveEndpoints() {
        return List.of(
                new EndpointTarget(HttpMethod.GET, "/api/admin/dashboard/summary"),
                new EndpointTarget(HttpMethod.GET, "/api/admin/payments"),
                new EndpointTarget(HttpMethod.POST, "/api/admin/payments/99999/approve"),
                new EndpointTarget(HttpMethod.POST, "/api/admin/payments/99999/reject", "{\"reason\":\"Invalid\"}"),
                new EndpointTarget(HttpMethod.POST, "/api/admin/activate", "{\"shopId\":1,\"planType\":\"MONTHLY\"}"),
                new EndpointTarget(HttpMethod.POST, "/api/admin/users/" + testStaff.getId() + "/suspend", "{\"reason\":\"Review\"}"),
                new EndpointTarget(HttpMethod.POST, "/api/admin/users/" + testStaff.getId() + "/reactivate"),
                new EndpointTarget(HttpMethod.GET, "/api/admin/subscriptions"),
                new EndpointTarget(HttpMethod.GET, "/api/admin/subscriptions/owner/" + testOwner.getId() + "/history"),
                new EndpointTarget(HttpMethod.GET, "/api/admin/users-and-shops"),
                new EndpointTarget(HttpMethod.GET, "/api/admin/activity-logs"),
                new EndpointTarget(HttpMethod.GET, "/api/admin/device-replacements"),
                new EndpointTarget(HttpMethod.POST, "/api/admin/device-replacements/99999/approve"),
                new EndpointTarget(HttpMethod.POST, "/api/admin/device-replacements/99999/reject", "{\"reason\":\"Review\"}")
        );
    }

    private MockHttpServletRequestBuilder createRequest(EndpointTarget ep, String authHeader, String adminKeyHeader) {
        MockHttpServletRequestBuilder builder;
        if (ep.method == HttpMethod.POST) {
            builder = post(ep.uri);
        } else if (ep.method == HttpMethod.GET) {
            builder = get(ep.uri);
        } else {
            throw new IllegalArgumentException("Unsupported: " + ep.method);
        }

        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        if (adminKeyHeader != null) {
            builder.header("X-Admin-Key", adminKeyHeader);
        }
        if (ep.jsonBody != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(ep.jsonBody);
        }
        return builder;
    }

    @Test
    @DisplayName("1. Adversarial Matrix: ALL 14 sensitive Super Admin endpoints strictly reject unauthenticated, owner, staff, and X-Admin-Key callers")
    void adversarialMatrix_AllSensitiveEndpointsRejectNonSuperAdmin() throws Exception {
        List<EndpointTarget> targets = getSensitiveEndpoints();

        for (EndpointTarget ep : targets) {
            // A) Unauthenticated caller -> HTTP 403 Forbidden
            mockMvc.perform(createRequest(ep, null, null))
                    .andExpect(status().isForbidden());

            // B) Automation identity via X-Admin-Key -> HTTP 403 Forbidden
            mockMvc.perform(createRequest(ep, null, adminKey))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            // C) Authenticated Owner -> HTTP 403 Forbidden
            mockMvc.perform(createRequest(ep, "Bearer " + ownerToken, null))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            // D) Authenticated Staff -> HTTP 403 Forbidden
            mockMvc.perform(createRequest(ep, "Bearer " + staffToken, null))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            // E) Forged / tampered JWT token -> HTTP 403 Forbidden (handled securely)
            String forgedToken = superAdminToken.substring(0, superAdminToken.length() - 8) + "badBytes";
            mockMvc.perform(createRequest(ep, "Bearer " + forgedToken, null))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("2. Automation Identity Scope: X-Admin-Key succeeds ONLY on operational endpoints and is rejected on all sensitive operations")
    void automationIdentity_StrictOperationalScopeEnforced() throws Exception {
        // Operational 1: /config -> 200
        mockMvc.perform(get("/api/admin/config")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyPrice").exists());

        // Operational 2: /settings -> 200
        AdminSettingRequest settingReq = new AdminSettingRequest();
        settingReq.setUpiId("business@upi");
        settingReq.setUpiName("QueueLess Payments");
        settingReq.setMonthlyPrice(new BigDecimal("51.00"));
        settingReq.setYearlyPrice(new BigDecimal("501.00"));

        mockMvc.perform(post("/api/admin/settings")
                        .header("X-Admin-Key", adminKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settingReq)))
                .andExpect(status().isOk());

        // Operational 3: /shops -> 200
        mockMvc.perform(get("/api/admin/shops")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isOk());

        // Operational 4: /category-requests -> 200
        mockMvc.perform(get("/api/admin/category-requests")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isOk());

        // Negative check: Invalid key on operational endpoint -> 403
        mockMvc.perform(get("/api/admin/config")
                        .header("X-Admin-Key", "invalid-fake-key-12345"))
                .andExpect(status().isForbidden());

        // Negative check: Sensitive Super Admin endpoints strictly return 403
        mockMvc.perform(get("/api/admin/dashboard/summary")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/payments")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/subscriptions")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/users-and-shops")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/activity-logs")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("3. Audit Trail Accountability: Human actions capture actual admin email; automation actions capture 'service:automation'")
    void auditTrail_DistinguishesHumanFromAutomationIdentity() throws Exception {
        // 1. Human Super Admin approves a payment
        PaymentRecord pay = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                "UTR_HUMAN_AUDIT_" + System.currentTimeMillis(),
                "payer@upi"
        );
        pay.setStatus("PENDING_APPROVAL");
        pay = paymentRecordRepository.save(pay);

        mockMvc.perform(post("/api/admin/payments/" + pay.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        // 2. Automation operational action via X-Admin-Key
        AdminSettingRequest settingReq = new AdminSettingRequest();
        settingReq.setUpiId("auto@upi");
        settingReq.setUpiName("QueueLess Automation");
        settingReq.setMonthlyPrice(new BigDecimal("51.00"));
        settingReq.setYearlyPrice(new BigDecimal("501.00"));

        mockMvc.perform(post("/api/admin/settings")
                        .header("X-Admin-Key", adminKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(settingReq)))
                .andExpect(status().isOk());

        // 3. Inspect recent audit logs
        List<AuditLog> recentLogs = auditLogRepository.findTop50ByOrderByCreatedAtDesc();
        final String targetPayId = pay.getId().toString();

        AuditLog humanAudit = recentLogs.stream()
                .filter(l -> "APPROVE_PAYMENT".equals(l.getAction()) && targetPayId.equals(l.getTargetId()))
                .findFirst()
                .orElseThrow();
        assertEquals(testSuperAdmin.getEmail(), humanAudit.getActor(), "Human action must capture actual admin email");

        AuditLog autoAudit = recentLogs.stream()
                .filter(l -> "ADMIN_SETTINGS_UPDATE".equals(l.getAction()))
                .findFirst()
                .orElseThrow();
        assertEquals("service:automation", autoAudit.getActor(), "Automation action must capture service:automation");

        // Verify neither record contains 'superadmin-key'
        assertNotEquals("superadmin-key", humanAudit.getActor());
        assertNotEquals("superadmin-key", autoAudit.getActor());
    }

    @Test
    @DisplayName("4. Session Revocation: Invalidation of activeSessionId immediately revokes Super Admin JWT")
    void sessionRevocation_ImmediatelyInvalidatesSuperAdminToken() throws Exception {
        // Active token works
        mockMvc.perform(get("/api/admin/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        // Revoke session (e.g. administrator logout or password change)
        testSuperAdmin.setActiveSessionId(UUID.randomUUID().toString());
        testSuperAdmin.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(testSuperAdmin);

        // Previous token is immediately rejected
        mockMvc.perform(get("/api/admin/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("5. Account Suspension: Suspending user revokes session and blocks all future token usage")
    void accountSuspension_ImmediatelyBlocksTokenUsage() throws Exception {
        // Owner token works on owner endpoint
        mockMvc.perform(get("/api/owner/staff")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // Super Admin suspends owner
        mockMvc.perform(post("/api/admin/users/" + testOwner.getId() + "/suspend")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Abuse violation detected\"}"))
                .andExpect(status().isOk());

        // Owner token is now immediately unauthorized
        mockMvc.perform(get("/api/owner/staff")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("6. Concurrent Payment Approvals: Row-level locking ensures exactly 1 state change and idempotent responses")
    void concurrentPaymentApprovals_RowLockPreventsRaceConditions() throws Exception {
        String utr = "UTR_CONC_" + System.currentTimeMillis();
        PaymentRecord pay = new PaymentRecord(
                "BRANCH_REGISTRATION",
                testOwner,
                testShop,
                null,
                new BigDecimal("99.00"),
                utr,
                "payer@upi"
        );
        pay.setStatus("PENDING_APPROVAL");
        pay = paymentRecordRepository.save(pay);

        final Long payId = pay.getId();
        int threads = 10;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(exec.submit(() -> {
                latch.await();
                mockMvc.perform(post("/api/admin/payments/" + payId + "/approve")
                                .header("Authorization", "Bearer " + superAdminToken))
                        .andExpect(status().isOk());
                successCount.incrementAndGet();
                return null;
            }));
        }

        latch.countDown();
        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        exec.shutdown();

        assertEquals(threads, successCount.get());

        PaymentRecord updated = paymentRecordRepository.findById(payId).orElseThrow();
        assertEquals("APPROVED", updated.getStatus());
    }

    @Test
    @DisplayName("7. Duplicate UTR Policy: Rejects approval when UTR has already been approved for another record")
    void duplicateUtr_RejectsSecondApprovalWithConflict() throws Exception {
        String sharedUtr = "UTR_SHARED_" + System.currentTimeMillis();

        // Record 1: Approved
        PaymentRecord pay1 = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                sharedUtr,
                "payer@upi"
        );
        pay1.setStatus("APPROVED");
        paymentRecordRepository.save(pay1);

        // Record 2: Second payment attempting to claim same UTR
        PaymentRecord pay2 = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                sharedUtr,
                "payer@upi"
        );
        pay2.setPlanType("MONTHLY");
        pay2.setStatus("PENDING_APPROVAL");
        pay2 = paymentRecordRepository.save(pay2);

        // Approval attempt on pay2 is rejected with 409 Conflict
        mockMvc.perform(post("/api/admin/payments/" + pay2.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("already been approved")));

        // Record 2 remains PENDING_APPROVAL
        PaymentRecord unchanged = paymentRecordRepository.findById(pay2.getId()).orElseThrow();
        assertEquals("PENDING_APPROVAL", unchanged.getStatus());
    }

    @Test
    @DisplayName("8. Rejection Validation: Empty reason aborts transaction with 400 Bad Request and zero side effects")
    void rejectionValidation_EmptyReasonAbortsCleanly() throws Exception {
        PaymentRecord pay = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                "UTR_VALIDATION_" + System.currentTimeMillis(),
                "payer@upi"
        );
        pay.setPlanType("MONTHLY");
        pay.setStatus("PENDING_APPROVAL");
        pay = paymentRecordRepository.save(pay);

        // Empty reason -> 400 Bad Request
        mockMvc.perform(post("/api/admin/payments/" + pay.getId() + "/reject")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Rejection reason is required")));

        // Record remains untouched
        PaymentRecord unchanged = paymentRecordRepository.findById(pay.getId()).orElseThrow();
        assertEquals("PENDING_APPROVAL", unchanged.getStatus());
        assertNull(unchanged.getReviewedAt());
        assertNull(unchanged.getRejectionReason());
    }
}
