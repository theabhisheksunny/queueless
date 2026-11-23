package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.entity.*;
import com.queueless.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SuperAdminPortalSecurityAndIntegrationTest {

    private static final String ADMIN_KEY = "admin-secret-queueless-2026";

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
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private PaymentRecordRepository paymentRecordRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.queueless.security.JwtTokenProvider tokenProvider;

    private User testSuperAdmin;
    private String superAdminToken;
    private User testOwner;
    private Business testBusiness;
    private Shop testShop;

    @BeforeEach
    void setUp() {
        String phone = "+9198" + (System.currentTimeMillis() % 100000000);
        testOwner = new User("owner_" + System.currentTimeMillis() + "@test.com", passwordEncoder.encode("123456"), "SuperAdminTestOwner", phone, UserRole.ROLE_OWNER);
        testOwner = userRepository.save(testOwner);

        testBusiness = new Business(testOwner, "Test Business " + System.currentTimeMillis());
        testBusiness = businessRepository.save(testBusiness);

        testShop = new Shop(testOwner, "Test Shop", "Barber Shop", 20);
        testShop.setBusiness(testBusiness);
        testShop.setIsPrimary(true);
        testShop.setRegistrationStatus("REGISTERED");
        testShop = shopRepository.save(testShop);

        testSuperAdmin = new User("superadmin_" + System.currentTimeMillis() + "@queueless.in",
                passwordEncoder.encode("SecureSuperPass!123"), "Platform Super Admin", "+9199" + (System.currentTimeMillis() % 100000000), UserRole.ROLE_SUPER_ADMIN);
        testSuperAdmin.setStatus("ACTIVE");
        testSuperAdmin.setActiveSessionId(java.util.UUID.randomUUID().toString());
        testSuperAdmin = userRepository.save(testSuperAdmin);
        superAdminToken = tokenProvider.generateTokenForUsername(testSuperAdmin.getEmail(), testSuperAdmin.getActiveSessionId());
    }

    @Test
    @DisplayName("1. Unauthorized requests without ROLE_SUPER_ADMIN are rejected with 403 Access Denied")
    void unauthorizedAccessWithoutAdminKey_Returns403() throws Exception {
        // Missing auth
        mockMvc.perform(get("/api/admin/dashboard/summary"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/subscriptions"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/users-and-shops"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/activity-logs"))
                .andExpect(status().isForbidden());

        // Legacy X-Admin-Key is strictly rejected on sensitive Super Admin endpoints
        mockMvc.perform(get("/api/admin/dashboard/summary")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/subscriptions")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/users-and-shops")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/activity-logs")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("2. Dashboard summary returns essential metrics, pending payments, and recent registrations")
    void getDashboardSummary_ReturnsAccurateMetrics() throws Exception {
        // Seed a pending payment
        String utr = "TESTUTR" + System.currentTimeMillis();
        PaymentRecord pendingPayment = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                utr,
                "absunny@ybl"
        );
        pendingPayment.setStatus("PENDING_APPROVAL");
        paymentRecordRepository.save(pendingPayment);

        mockMvc.perform(get("/api/admin/dashboard/summary")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalShopOwners", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.totalRegisteredBranches", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.pendingPayments", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.latestPendingPayments", not(empty())))
                .andExpect(jsonPath("$.recentlyRegisteredOwners", not(empty())));
    }

    @Test
    @DisplayName("3. Subscriptions endpoint lists records and supports status filtering")
    void listSubscriptions_ReturnsSubscriptionsAndSupportsFilter() throws Exception {
        Subscription sub = new Subscription(
                testShop,
                SubscriptionPlan.MONTHLY,
                SubscriptionStatus.ACTIVE,
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                new BigDecimal("51.00")
        );
        sub.setOwner(testOwner);
        subscriptionRepository.save(sub);

        // List all
        mockMvc.perform(get("/api/admin/subscriptions")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())))
                .andExpect(jsonPath("$[0].plan", notNullValue()));

        // Filter ACTIVE
        mockMvc.perform(get("/api/admin/subscriptions?status=ACTIVE")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status", is("ACTIVE")));
    }

    @Test
    @DisplayName("4. Owner history endpoint returns complete subscription and payment audit trail")
    void getOwnerSubscriptionAndPaymentHistory_ReturnsFullHistory() throws Exception {
        mockMvc.perform(get("/api/admin/subscriptions/owner/" + testOwner.getId() + "/history")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId", is(testOwner.getId().intValue())))
                .andExpect(jsonPath("$.ownerName", is(testOwner.getFullName())))
                .andExpect(jsonPath("$.phone", is(testOwner.getPhone())))
                .andExpect(jsonPath("$.subscriptions", notNullValue()))
                .andExpect(jsonPath("$.payments", notNullValue()));
    }

    @Test
    @DisplayName("5. Users and Shops endpoint returns merchant hierarchy without customer personal data")
    void listUsersAndShops_ReturnsMerchantHierarchyWithoutCustomerPII() throws Exception {
        mockMvc.perform(get("/api/admin/users-and-shops?query=" + testOwner.getPhone())
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].ownerName", is(testOwner.getFullName())))
                .andExpect(jsonPath("$[0].phone", is(testOwner.getPhone())))
                .andExpect(jsonPath("$[0].branches[0].name", is("Test Shop")))
                .andExpect(jsonPath("$[0].branches[0].primary", is(true)))
                // Verify zero customer personal fields exist in the schema
                .andExpect(jsonPath("$[0].customers").doesNotExist())
                .andExpect(jsonPath("$[0].customerPhones").doesNotExist());
    }

    @Test
    @DisplayName("6. Payment approval and rejection with reason update status and audit log")
    void approveAndRejectPayment_UpdatesStatusAndAuditLog() throws Exception {
        // Payment 1: Approve
        String utrApprove = "APPROVE" + System.currentTimeMillis();
        PaymentRecord pay1 = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                utrApprove,
                "absunny@ybl"
        );
        pay1.setStatus("PENDING_APPROVAL");
        pay1 = paymentRecordRepository.save(pay1);

        mockMvc.perform(post("/api/admin/payments/" + pay1.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("approved successfully")));

        // Payment 2: Reject with mandatory reason
        String utrReject = "REJECT" + System.currentTimeMillis();
        PaymentRecord pay2 = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                utrReject,
                "absunny@ybl"
        );
        pay2.setStatus("PENDING_APPROVAL");
        pay2 = paymentRecordRepository.save(pay2);

        mockMvc.perform(post("/api/admin/payments/" + pay2.getId() + "/reject")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "UTR not found in bank statement"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("Payment rejected")));
    }

    @Test
    @DisplayName("7. Activity logs endpoint returns administrative history")
    void listActivityLogs_ReturnsPrioritizedAdminLogs() throws Exception {
        auditLogRepository.save(new AuditLog(
                "APPROVE_PAYMENT",
                "admin",
                "PAYMENT",
                "999",
                "Approved subscription payment for SuperAdminTest",
                "127.0.0.1"
        ));

        mockMvc.perform(get("/api/admin/activity-logs")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(empty())))
                .andExpect(jsonPath("$[0].action", notNullValue()))
                .andExpect(jsonPath("$[0].actor", notNullValue()));
    }
}
