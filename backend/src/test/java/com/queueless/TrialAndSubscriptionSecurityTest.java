package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.AdminShopActivateRequest;
import com.queueless.dto.CustomerManualAddRequest;
import com.queueless.dto.PasswordResetRequest;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.SubscriptionPlan;
import com.queueless.exception.SubscriptionRequiredException;
import com.queueless.exception.TrialAlreadyConsumedException;
import com.queueless.repository.TrialEligibilityLedgerRepository;
import com.queueless.repository.UserRepository;
import com.queueless.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TrialAndSubscriptionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private QueueService queueService;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private TrialEligibilityLedgerRepository ledgerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestRegistrationHelper testRegistrationHelper;

    private Clock baseClock;

    @BeforeEach
    void setUp() {
        baseClock = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneId.of("UTC"));
        authService.setClock(baseClock);
        entitlementService.setClock(baseClock);
        subscriptionService.setClock(baseClock);
        queueService.setClock(baseClock);
    }

    @Test
    @DisplayName("1 & 2 & 16. Duplicate Shop Names: Two owners register identical shop names; both operate independently with zero data leakage")
    void testDuplicateShopNamesOperateIndependently() throws Exception {
        String sharedShopName = "Royal Hair Salon & Spa";

        // Owner 1 Registration
        RegisterRequest req1 = new RegisterRequest();
        req1.setFullName("Aakash Verma");
        req1.setEmail("aakash_" + System.currentTimeMillis() + "@test.com");
        req1.setPassword("secretPass1");
        req1.setPhone("98111" + (10000 + (int)(Math.random() * 89999)));
        testRegistrationHelper.preparePin(req1);
        req1.setShopName(sharedShopName); // Identical shop name
        req1.setCategory("Salon & Spa");
        var auth1 = authService.register(req1);
        Long shopId1 = auth1.getShopId();

        // Owner 2 Registration with identical shop name
        RegisterRequest req2 = new RegisterRequest();
        req2.setFullName("Deepak Chopra");
        req2.setEmail("deepak_" + System.currentTimeMillis() + "@test.com");
        req2.setPassword("secretPass2");
        req2.setPhone("98222" + (10000 + (int)(Math.random() * 89999)));
        testRegistrationHelper.preparePin(req2);
        req2.setShopName(sharedShopName); // Identical shop name
        req2.setCategory("Salon & Spa");
        var auth2 = authService.register(req2);
        Long shopId2 = auth2.getShopId();

        // Verification 1: Both registered successfully with distinct internal IDs
        assertThat(shopId1).isNotNull();
        assertThat(shopId2).isNotNull();
        assertThat(shopId1).isNotEqualTo(shopId2);

        // Verification 2: Start queue for both shops independently
        var queue1 = queueService.startQueue(shopId1);
        var queue2 = queueService.startQueue(shopId2);
        assertThat(queue1.getPublicQueueId()).isNotEqualTo(queue2.getPublicQueueId());

        // Verification 3: Add customer to Shop 1
        CustomerManualAddRequest addReq1 = new CustomerManualAddRequest();
        addReq1.setCustomerName("Shop1 Customer Amit");
        queueService.manualAddCustomer(shopId1, addReq1);

        // Verification 4: Add customer to Shop 2
        CustomerManualAddRequest addReq2 = new CustomerManualAddRequest();
        addReq2.setCustomerName("Shop2 Customer Priya");
        queueService.manualAddCustomer(shopId2, addReq2);

        // Verification 16: Zero data leakage - check dashboards
        var dash1 = queueService.getDashboard(shopId1);
        var dash2 = queueService.getDashboard(shopId2);

        assertThat(dash1.getTotalCustomers()).isEqualTo(1);
        assertThat(dash1.getCustomers().get(0).getCustomerName()).isEqualTo("Shop1 Customer Amit");

        assertThat(dash2.getTotalCustomers()).isEqualTo(1);
        assertThat(dash2.getCustomers().get(0).getCustomerName()).isEqualTo("Shop2 Customer Priya");

        // Verify direct API token isolation
        mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + auth1.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value(sharedShopName))
                .andExpect(jsonPath("$.customers[0].customerName").value("Shop1 Customer Amit"));

        mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + auth2.getToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value(sharedShopName))
                .andExpect(jsonPath("$.customers[0].customerName").value("Shop2 Customer Priya"));
    }

    @Test
    @DisplayName("3 & 4 & 5 & 8. Anti-Abuse: Same verified mobile number rejected on re-registration, device change, or shop rename")
    void testSameVerifiedMobileAttemptsRejected() {
        String testPhone = "9876540001";

        RegisterRequest firstReq = new RegisterRequest();
        firstReq.setFullName("Original Owner");
        firstReq.setEmail("orig_" + System.currentTimeMillis() + "@test.com");
        firstReq.setPassword("password123");
        firstReq.setPhone(testPhone);
        testRegistrationHelper.preparePin(firstReq);
        firstReq.setShopName("Original Shop");
        firstReq.setDeviceFingerprint("device-fingerprint-desktop-chrome");
        var authRes = authService.register(firstReq);
        assertThat(authRes.getToken()).isNotNull();

        // 3. Same mobile attempts second registration with different email -> REJECTED
        RegisterRequest secondReq = new RegisterRequest();
        secondReq.setFullName("Another Name");
        secondReq.setEmail("second_" + System.currentTimeMillis() + "@test.com");
        secondReq.setPassword("password123");
        secondReq.setPin("123456");
        secondReq.setConfirmPin("123456");
        secondReq.setPhone("+91 98765 40001"); // variation in formatting
        secondReq.setShopName("Another Shop");

        assertThrows(IllegalArgumentException.class, () -> authService.register(secondReq),
                "Should reject registration because phone is already associated with an account");

        // 4 & 8. Device / Browser change with same phone -> REJECTED
        RegisterRequest deviceChangeReq = new RegisterRequest();
        deviceChangeReq.setFullName("Mobile User");
        deviceChangeReq.setEmail("device_" + System.currentTimeMillis() + "@test.com");
        deviceChangeReq.setPassword("password123");
        deviceChangeReq.setPin("123456");
        deviceChangeReq.setConfirmPin("123456");
        deviceChangeReq.setPhone("09876540001"); // variation with trunk 0
        deviceChangeReq.setShopName("New Shop Name");
        deviceChangeReq.setDeviceFingerprint("device-fingerprint-iphone-safari");

        assertThrows(IllegalArgumentException.class, () -> authService.register(deviceChangeReq),
                "Should reject registration regardless of device fingerprint change");

        // 5. Changing shop name to attempt another trial -> REJECTED
        RegisterRequest renameTrialReq = new RegisterRequest();
        renameTrialReq.setFullName("Original Owner");
        renameTrialReq.setEmail("rename_" + System.currentTimeMillis() + "@test.com");
        renameTrialReq.setPassword("password123");
        renameTrialReq.setPin("123456");
        renameTrialReq.setConfirmPin("123456");
        renameTrialReq.setPhone("9876540001");
        renameTrialReq.setShopName("Brand New Rename Shop");

        assertThrows(IllegalArgumentException.class, () -> authService.register(renameTrialReq));
    }

    @Test
    @DisplayName("6. Account deletion followed by re-registration — Ledger prevents new trial")
    void testAccountDeletionFollowedByReRegistrationHasNoNewTrial() {
        String testPhone = "9876540002";
        String email = "delete_me_" + System.currentTimeMillis() + "@test.com";

        RegisterRequest req = new RegisterRequest();
        req.setFullName("Deletable Owner");
        req.setEmail(email);
        req.setPassword("pass1234");
        req.setPhone(testPhone);
        testRegistrationHelper.preparePin(req);
        req.setShopName("Temporary Clinic");
        authService.register(req);

        // Delete account
        authService.deleteAccount(email);

        // Verify account is removed from users table
        assertThat(userRepository.findByEmail(email)).isEmpty();

        // Verify ledger record survives account deletion
        var ledgerEntry = ledgerRepository.findByNormalizedPhone("+919876540002");
        assertThat(ledgerEntry).isPresent();
        assertThat(ledgerEntry.get().getRegistrationStatus()).isEqualTo("ACCOUNT_DELETED");

        // Attempt to re-register using the same mobile number
        RegisterRequest reRegReq = new RegisterRequest();
        reRegReq.setFullName("Re-registering Owner");
        reRegReq.setEmail("fresh_email_" + System.currentTimeMillis() + "@test.com");
        reRegReq.setPassword("pass1234");
        reRegReq.setPin("123456");
        reRegReq.setConfirmPin("123456");
        reRegReq.setPhone(testPhone);
        reRegReq.setShopName("Re-opened Clinic");

        // Must be rejected by the persistent trial eligibility ledger
        assertThrows(TrialAlreadyConsumedException.class, () -> authService.register(reRegReq),
                "Account deletion must not allow claiming a fresh trial");
    }

    @Test
    @DisplayName("7 & 17. Password reset & Account recovery preserves subscription and trial history with NO new trial")
    void testPasswordResetPreservesSubscriptionHistory() throws Exception {
        String testPhone = "9876540003";
        String email = "recovery_" + System.currentTimeMillis() + "@test.com";

        RegisterRequest req = new RegisterRequest();
        req.setFullName("Recovery Owner");
        req.setEmail(email);
        req.setPassword("oldPassword123");
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setPhone(testPhone);
        testRegistrationHelper.preparePin(req);
        req.setShopName("Secure Clinic");
        var authRes = authService.register(req);
        Long shopId = authRes.getShopId();

        // Advance clock by 3 days
        Clock day3Clock = Clock.offset(baseClock, Duration.ofDays(3));
        authService.setClock(day3Clock);
        entitlementService.setClock(day3Clock);
        subscriptionService.setClock(day3Clock);

        // Perform password reset using current password/PIN
        PasswordResetRequest resetReq = new PasswordResetRequest(testPhone, "123456", "newPassword456");
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isOk());

        // Verify subscription remains 4 days remaining (7 - 3 = 4), not reset to 7
        var status = subscriptionService.getShopSubscriptionStatus(shopId);
        assertThat(status.isActive()).isTrue();
        assertThat(status.getDaysRemaining()).isEqualTo(4);

        // Verify login works with new password
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.queueless.dto.AuthRequest(email, "newPassword456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionActive").value(true))
                .andExpect(jsonPath("$.subscriptionDaysRemaining").value(4));
    }

    @Test
    @DisplayName("9. Concurrent registrations with same verified mobile — Only ONE trial can be consumed")
    void testConcurrentRegistrationsOnlyOneTrialConsumed() throws Exception {
        String concurrentPhone = "9876540009";
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    RegisterRequest req = new RegisterRequest();
                    req.setFullName("Concurrent Owner " + idx);
                    req.setEmail("concurrent_" + idx + "_" + System.currentTimeMillis() + "@test.com");
                    req.setPassword("password123");
                    req.setPin("123456");
                    req.setConfirmPin("123456");
                    req.setPhone(concurrentPhone);
                    req.setShopName("Concurrent Shop " + idx);

                    authService.register(req);
                    successCount.incrementAndGet();
                } catch (Exception ex) {
                    rejectCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly ONE transaction can succeed
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(rejectCount.get()).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("10 & 11 & 12 & 13. 7-Day Trial boundary: Active before 7 days, expires at exact boundary, direct API access blocked with 402")
    void testSevenDayTrialLifecycleAndBoundaryEnforcement() throws Exception {
        String phone = "9876540010";
        String email = "trial_boundary_" + System.currentTimeMillis() + "@test.com";

        RegisterRequest req = new RegisterRequest();
        req.setFullName("Boundary Tester");
        req.setEmail(email);
        req.setPassword("pass123");
        req.setPhone(phone);
        testRegistrationHelper.preparePin(req);
        req.setShopName("Boundary Salon");
        var authRes = authService.register(req);
        Long shopId = authRes.getShopId();
        String token = authRes.getToken();

        // 10. Trial works before expiry (Day 0 to Day 6)
        Clock day6Clock = Clock.offset(baseClock, Duration.ofDays(6).plusHours(23));
        authService.setClock(day6Clock);
        entitlementService.setClock(day6Clock);
        subscriptionService.setClock(day6Clock);
        queueService.setClock(day6Clock);

        // Verification: Entitlement is active
        assertThat(entitlementService.hasActiveEntitlement(shopId)).isTrue();
        mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 11. Trial expires at exact boundary (Day 7 + 1 second)
        Clock day7ExpiredClock = Clock.offset(baseClock, Duration.ofDays(7).plusSeconds(1));
        authService.setClock(day7ExpiredClock);
        entitlementService.setClock(day7ExpiredClock);
        subscriptionService.setClock(day7ExpiredClock);
        queueService.setClock(day7ExpiredClock);

        // Entitlement is now FALSE
        assertThat(entitlementService.hasActiveEntitlement(shopId)).isFalse();

        // 13. Direct backend API access after expiry returns HTTP 402 Payment Required
        mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402))
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"));

        mockMvc.perform(post("/api/queue/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"));

        // 12. Frontend manipulation cannot extend trial - Subscription status endpoint confirms expiry
        mockMvc.perform(get("/api/owner/subscription/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.trialExpired").value(true));
    }

    @Test
    @DisplayName("14 & 15. Paid Subscription: Admin activation permits access; expiry blocks protected operations")
    void testPaidSubscriptionPermitsAndExpiryBlocks() throws Exception {
        String phone = "9876540014";
        String email = "paid_sub_" + System.currentTimeMillis() + "@test.com";

        RegisterRequest req = new RegisterRequest();
        req.setFullName("Subscribed Owner");
        req.setEmail(email);
        req.setPassword("pass123");
        req.setPhone(phone);
        testRegistrationHelper.preparePin(req);
        req.setShopName("Paid Salon");
        var authRes = authService.register(req);
        Long shopId = authRes.getShopId();
        String token = authRes.getToken();

        // Advance clock past the 7-day trial so trial is expired
        Clock trialExpiredClock = Clock.offset(baseClock, Duration.ofDays(8));
        entitlementService.setClock(trialExpiredClock);
        subscriptionService.setClock(trialExpiredClock);
        queueService.setClock(trialExpiredClock);

        assertThat(entitlementService.hasActiveEntitlement(shopId)).isFalse();

        // 14. Admin activates paid Monthly subscription (30 days)
        AdminShopActivateRequest activateReq = new AdminShopActivateRequest();
        activateReq.setShopId(shopId);
        activateReq.setPlanType(SubscriptionPlan.MONTHLY);
        activateReq.setCustomDays(30);

        subscriptionService.adminActivateShop(activateReq);

        // Verification: Access is now permitted
        assertThat(entitlementService.hasActiveEntitlement(shopId)).isTrue();
        mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 15. Advance clock past the 30-day paid subscription (39 days from base)
        Clock paidExpiredClock = Clock.offset(baseClock, Duration.ofDays(39));
        entitlementService.setClock(paidExpiredClock);
        subscriptionService.setClock(paidExpiredClock);
        queueService.setClock(paidExpiredClock);

        // Verification: Protected operations blocked again with HTTP 402
        assertThat(entitlementService.hasActiveEntitlement(shopId)).isFalse();
        mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"));
    }
}
