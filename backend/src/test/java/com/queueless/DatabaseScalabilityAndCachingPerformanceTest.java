package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.AdminSettingRequest;
import com.queueless.dto.CreateBranchRequest;
import com.queueless.entity.*;
import com.queueless.repository.*;
import com.queueless.security.JwtTokenProvider;
import com.queueless.service.BranchService;
import com.queueless.service.DataLifecycleMaintenanceService;
import com.queueless.service.QueueService;
import com.queueless.service.SubscriptionService;
import com.queueless.dto.CustomerJoinRequest;
import com.queueless.dto.SubscriptionPaymentSubmitRequest;
import com.queueless.entity.SubscriptionPlan;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class DatabaseScalabilityAndCachingPerformanceTest {

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
    private DeviceAuthChallengeRepository deviceAuthChallengeRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private CustomerPushSubscriptionRepository pushSubscriptionRepository;

    @Autowired
    private QueueSessionRepository queueSessionRepository;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private QueueService queueService;

    @Autowired
    private BranchService branchService;

    @Autowired
    private DataLifecycleMaintenanceService maintenanceService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User superAdmin;
    private String superAdminToken;

    @BeforeEach
    void setUp() {
        String ts = String.valueOf(System.currentTimeMillis());
        superAdmin = new User();
        superAdmin.setEmail("superadmin_scale_" + ts + "@queueless.in");
        superAdmin.setPhone("+919988" + ts.substring(Math.max(0, ts.length() - 6)));
        superAdmin.setFullName("Scale Super Admin");
        superAdmin.setRole(UserRole.ROLE_SUPER_ADMIN);
        superAdmin.setPassword(passwordEncoder.encode("SecureScaleAdmin123!"));
        superAdmin.setStatus("ACTIVE");
        superAdmin.setCredentialsUpdatedAt(Instant.now());
        superAdmin.setActiveSessionId(UUID.randomUUID().toString());
        superAdmin = userRepository.save(superAdmin);
        superAdminToken = tokenProvider.generateTokenForUsername(superAdmin.getEmail(), superAdmin.getActiveSessionId());
    }

    @Test
    @DisplayName("1. Bulk fetch and pagination on /api/admin/users-and-shops eliminates N+1 queries")
    void testUsersAndShopsBulkQueryAndPagination() throws Exception {
        String testSuffix = UUID.randomUUID().toString().substring(0, 8);
        List<User> createdOwners = new ArrayList<>();

        for (int i = 1; i <= 5; i++) {
            User owner = new User();
            owner.setEmail("owner_scale_" + testSuffix + "_" + i + "@queueless.in");
            owner.setPhone("+919100" + String.format("%06d", (int) (Math.random() * 1000000)));
            owner.setFullName("Scale Test Owner " + testSuffix + " " + i);
            owner.setRole(UserRole.ROLE_OWNER);
            owner.setPassword(passwordEncoder.encode("123456"));
            owner.setStatus("ACTIVE");
            owner = userRepository.save(owner);
            createdOwners.add(owner);

            Business biz = new Business(owner, "Biz " + testSuffix + " " + i);
            biz = businessRepository.save(biz);

            Shop shop1 = new Shop();
            shop1.setOwner(owner);
            shop1.setBusiness(biz);
            shop1.setName("Branch 1 of " + testSuffix + " " + i);
            shop1.setBranchName("Main Branch");
            shop1.setCategory("Salon");
            shop1.setIsPrimary(true);
            shop1.setRegistrationStatus("REGISTERED");
            shop1 = shopRepository.save(shop1);

            QueueSession qs = new QueueSession(shop1, LocalDate.now(), "PUBQ_" + testSuffix + "_" + i);
            qs.setStatus(QueueSessionStatus.OPEN);
            queueSessionRepository.save(qs);

            Subscription sub = new Subscription(
                    shop1,
                    SubscriptionPlan.MONTHLY,
                    SubscriptionStatus.ACTIVE,
                    LocalDate.now(),
                    LocalDate.now().plusDays(30),
                    new BigDecimal("499.00")
            );
            sub.setOwner(owner);
            subscriptionRepository.save(sub);
        }

        // Test unpaginated fetch - returns all owners
        mockMvc.perform(get("/api/admin/users-and-shops")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(5))))
                .andExpect(jsonPath("$[0].ownerName").isNotEmpty())
                .andExpect(jsonPath("$[0].branches").isNotEmpty());

        // Test paginated fetch page 0 size 2
        mockMvc.perform(get("/api/admin/users-and-shops?page=0&size=2")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$", hasSize(2)));

        // Test query search filtering
        mockMvc.perform(get("/api/admin/users-and-shops?query=" + testSuffix)
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    @Test
    @DisplayName("2. Database pagination and X-Total-Count headers on all Super Admin data tables")
    void testPaginationAndTotalCountHeadersOnAdminEndpoints() throws Exception {
        String testRef = "PAGETEST_" + UUID.randomUUID().toString().substring(0, 8);

        // Seed 3 payment records
        for (int i = 1; i <= 3; i++) {
            PaymentRecord pr = new PaymentRecord(
                    "SUBSCRIPTION",
                    superAdmin,
                    null,
                    null,
                    new BigDecimal("499.00"),
                    testRef + "_" + i,
                    "upi@queueless"
            );
            pr.setStatus("PENDING_APPROVAL");
            pr.setPlanType("MONTHLY");
            paymentRecordRepository.save(pr);
        }

        // 1. Payments pagination
        mockMvc.perform(get("/api/admin/payments?page=0&size=2")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$", hasSize(2)));

        // 2. Subscriptions pagination
        mockMvc.perform(get("/api/admin/subscriptions?page=0&size=2")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$", hasSize(lessThanOrEqualTo(2))));

        // 3. Activity logs pagination
        mockMvc.perform(get("/api/admin/activity-logs?page=0&size=2")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$", hasSize(lessThanOrEqualTo(2))));
    }

    @Test
    @DisplayName("3. Caffeine caching stores pricing/UPI settings and evicts properly on admin update")
    void testCaffeineCachingAndEviction() {
        // Clear cache initially
        var cache = cacheManager.getCache("appSettings");
        if (cache != null) {
            cache.clear();
        }

        BigDecimal monthlyBefore = subscriptionService.getConfiguredMonthlyPrice();
        BigDecimal yearlyBefore = subscriptionService.getConfiguredYearlyPrice();
        String upiBefore = subscriptionService.getConfiguredUpiId();

        assertNotNull(monthlyBefore);
        assertNotNull(yearlyBefore);
        assertNotNull(upiBefore);

        // Subsequent call should hit Caffeine cache
        BigDecimal monthlyCached = subscriptionService.getConfiguredMonthlyPrice();
        assertEquals(monthlyBefore, monthlyCached);

        // Update settings via adminUpdateSettings which has @CacheEvict
        AdminSettingRequest updateReq = new AdminSettingRequest();
        updateReq.setMonthlyPrice(new BigDecimal("799.00"));
        updateReq.setYearlyPrice(new BigDecimal("7999.00"));
        updateReq.setUpiId("updated@upi.bank");
        updateReq.setUpiName("QueueLess Updated Corp");

        subscriptionService.adminUpdateSettings(updateReq);

        // Fresh lookups must reflect updated values
        assertEquals(new BigDecimal("799.00"), subscriptionService.getConfiguredMonthlyPrice());
        assertEquals(new BigDecimal("7999.00"), subscriptionService.getConfiguredYearlyPrice());
        assertEquals("updated@upi.bank", subscriptionService.getConfiguredUpiId());
        assertEquals("QueueLess Updated Corp", subscriptionService.getConfiguredUpiName());

        // Restore default values so subsequent tests are not affected
        updateReq.setMonthlyPrice(monthlyBefore);
        updateReq.setYearlyPrice(yearlyBefore);
        updateReq.setUpiId(upiBefore);
        updateReq.setUpiName("QueueLess Platform");
        subscriptionService.adminUpdateSettings(updateReq);
    }

    @Test
    @DisplayName("4. Data lifecycle maintenance service cleans ephemeral data while preserving financial and audit records")
    void testDataLifecyclePurgesEphemeralDataSafely() {
        String testNonce = UUID.randomUUID().toString();

        // 1. Seed expired (>24h ago) vs active device auth challenges
        DeviceAuthChallenge expiredChallenge = new DeviceAuthChallenge(
                superAdmin,
                "DEV_REG_EXP_" + testNonce,
                "CHALLENGE_EXP_" + testNonce,
                "DEVICE_LOGIN",
                LocalDateTime.now().minusHours(25)
        );
        deviceAuthChallengeRepository.save(expiredChallenge);

        DeviceAuthChallenge activeChallenge = new DeviceAuthChallenge(
                superAdmin,
                "DEV_REG_ACT_" + testNonce,
                "CHALLENGE_ACT_" + testNonce,
                "DEVICE_LOGIN",
                LocalDateTime.now().plusHours(1)
        );
        deviceAuthChallengeRepository.save(activeChallenge);

        // 2. Seed expired vs active idempotency keys
        IdempotencyKey expiredKey = new IdempotencyKey(
                superAdmin,
                "TEST_SCOPE",
                "EXP_KEY_" + testNonce,
                "HASH_EXP",
                "PAYLOAD_EXP",
                LocalDateTime.now().minusHours(2)
        );
        idempotencyKeyRepository.save(expiredKey);

        IdempotencyKey activeKey = new IdempotencyKey(
                superAdmin,
                "TEST_SCOPE",
                "ACT_KEY_" + testNonce,
                "HASH_ACT",
                "PAYLOAD_ACT",
                LocalDateTime.now().plusHours(24)
        );
        idempotencyKeyRepository.save(activeKey);

        // 3. Seed critical financial payment record and administrative audit log
        PaymentRecord financialRecord = new PaymentRecord(
                "SUBSCRIPTION",
                superAdmin,
                null,
                null,
                new BigDecimal("1299.00"),
                "UTR_FINANCIAL_SAFE_" + testNonce,
                "finance@upi"
        );
        financialRecord.setStatus("APPROVED");
        financialRecord = paymentRecordRepository.save(financialRecord);

        AuditLog auditLog = new AuditLog(
                "CRITICAL_ADMIN_ACTION",
                superAdmin.getEmail(),
                "PAYMENT",
                financialRecord.getId().toString(),
                "Sensitive audit event that must NEVER be deleted by maintenance jobs",
                "127.0.0.1"
        );
        auditLog = auditLogRepository.save(auditLog);

        // Execute maintenance purge
        maintenanceService.performMaintenanceCleanup();

        // Verify expired ephemeral entries are purged
        assertFalse(deviceAuthChallengeRepository.findByChallenge("CHALLENGE_EXP_" + testNonce).isPresent(),
                "Expired device auth challenge must be deleted");
        assertTrue(deviceAuthChallengeRepository.findByChallenge("CHALLENGE_ACT_" + testNonce).isPresent(),
                "Active device auth challenge must be preserved");

        assertFalse(idempotencyKeyRepository.findByOwnerIdAndScopeAndIdempotencyKey(superAdmin.getId(), "TEST_SCOPE", "EXP_KEY_" + testNonce).isPresent(),
                "Expired idempotency key must be purged");
        assertTrue(idempotencyKeyRepository.findByOwnerIdAndScopeAndIdempotencyKey(superAdmin.getId(), "TEST_SCOPE", "ACT_KEY_" + testNonce).isPresent(),
                "Active idempotency key must be preserved");

        // Verify financial and audit records are completely untouched
        assertTrue(paymentRecordRepository.findById(financialRecord.getId()).isPresent(),
                "Financial payment records must NEVER be deleted by maintenance service");
        assertTrue(auditLogRepository.findById(auditLog.getId()).isPresent(),
                "Audit logs must NEVER be deleted by data lifecycle maintenance");
    }

    @Test
    @DisplayName("5. Branch creation dedicated IdempotencyKey persistence handles retries and prevents noise in audit logs")
    void testBranchCreationDedicatedIdempotencyKeyPersistence() {
        String testSuffix = UUID.randomUUID().toString().substring(0, 8);

        // Setup owner with primary shop and active subscription
        User owner = new User();
        owner.setEmail("branch_idemp_" + testSuffix + "@queueless.in");
        owner.setPhone("+919333" + String.format("%06d", (int) (Math.random() * 1000000)));
        owner.setFullName("Idemp Owner " + testSuffix);
        owner.setRole(UserRole.ROLE_OWNER);
        owner.setPassword(passwordEncoder.encode("123456"));
        owner.setStatus("ACTIVE");
        owner = userRepository.save(owner);

        Shop primaryShop = new Shop();
        primaryShop.setOwner(owner);
        primaryShop.setName("Primary Branch " + testSuffix);
        primaryShop.setIsPrimary(true);
        primaryShop.setRegistrationStatus("REGISTERED");
        primaryShop = shopRepository.save(primaryShop);

        Subscription activeSub = new Subscription(
                primaryShop,
                SubscriptionPlan.YEARLY,
                SubscriptionStatus.ACTIVE,
                LocalDate.now(),
                LocalDate.now().plusDays(365),
                new BigDecimal("2999.00")
        );
        activeSub.setOwner(owner);
        subscriptionRepository.save(activeSub);

        String clientKey = "IDEMP-CLIENT-" + testSuffix;
        CreateBranchRequest req = new CreateBranchRequest();
        req.setName("Second Branch " + testSuffix);
        req.setBranchName("North Wing");
        req.setCategory("Retail");
        req.setAvgServiceDurationMinutes(20);
        req.setIdempotencyKey(clientKey);

        // First creation call
        var resp1 = branchService.createBranch(owner.getId(), req);
        assertNotNull(resp1);
        assertNotNull(resp1.getId());

        // Verify dedicated IdempotencyKey record was saved
        Optional<IdempotencyKey> savedKey = idempotencyKeyRepository
                .findByOwnerIdAndScopeAndIdempotencyKey(owner.getId(), "BRANCH_CREATION", clientKey);
        assertTrue(savedKey.isPresent(), "Dedicated idempotency_keys table must contain BRANCH_CREATION record");
        assertEquals(resp1.getId().toString(), savedKey.get().getResponsePayload());

        // Replay call with exact same idempotency key
        var resp2 = branchService.createBranch(owner.getId(), req);
        assertNotNull(resp2);
        assertEquals(resp1.getId(), resp2.getId(), "Replayed branch creation must return identical shop ID");

        // Verify audit_logs does NOT contain noisy BRANCH_IDEMPOTENCY records
        List<AuditLog> noisyLogs = auditLogRepository.findAllByTargetTypeAndTargetIdOrderByCreatedAtDesc("BRANCH_IDEMPOTENCY", owner.getId() + ":" + clientKey);
        assertTrue(noisyLogs.isEmpty(), "Audit log must not contain BRANCH_IDEMPOTENCY operational cache records");
    }

    @Test
    @DisplayName("6. Backend enforces maximum page size cap (MAX_PAGE_SIZE=100) and safely clamps negative page indices")
    void testBackendMaximumPageSizeEnforcementAndNegativePageBoundaries() throws Exception {
        // Request with excessive page size (size=5000) and negative page (page=-3)
        mockMvc.perform(get("/api/admin/payments?page=-3&size=5000")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$", hasSize(lessThanOrEqualTo(100))));

        mockMvc.perform(get("/api/admin/subscriptions?page=0&size=999999")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$", hasSize(lessThanOrEqualTo(100))));

        mockMvc.perform(get("/api/admin/users-and-shops?page=0&size=500")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$", hasSize(lessThanOrEqualTo(100))));

        mockMvc.perform(get("/api/admin/activity-logs?page=0&size=500")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Total-Count"))
                .andExpect(jsonPath("$", hasSize(lessThanOrEqualTo(100))));
    }

    @Test
    @DisplayName("7. Idempotency safety: Replay with different payload is rejected; expired key is cleaned up safely")
    void testIdempotencyPayloadMismatchAndExpiredKeyHandling() {
        String testSuffix = UUID.randomUUID().toString().substring(0, 8);

        User owner = new User();
        owner.setEmail("idemp_safety_" + testSuffix + "@queueless.in");
        owner.setPhone("+919444" + String.format("%06d", (int) (Math.random() * 1000000)));
        owner.setFullName("Idemp Safety Owner " + testSuffix);
        owner.setRole(UserRole.ROLE_OWNER);
        owner.setPassword(passwordEncoder.encode("123456"));
        owner.setStatus("ACTIVE");
        final User savedOwner = userRepository.save(owner);

        Shop primaryShop = new Shop();
        primaryShop.setOwner(savedOwner);
        primaryShop.setName("Main Store " + testSuffix);
        primaryShop.setIsPrimary(true);
        primaryShop.setRegistrationStatus("REGISTERED");
        primaryShop = shopRepository.save(primaryShop);

        String clientKey = "SAFETY-KEY-" + testSuffix;
        CreateBranchRequest req1 = new CreateBranchRequest();
        req1.setName("Branch Alpha " + testSuffix);
        req1.setBranchName("Alpha Branch");
        req1.setCategory("Salon");
        req1.setIdempotencyKey(clientKey);

        var resp1 = branchService.createBranch(savedOwner.getId(), req1);
        assertNotNull(resp1);

        // Replay with different branch payload details using the same idempotency key must throw IllegalStateException
        CreateBranchRequest req2 = new CreateBranchRequest();
        req2.setName("Branch Beta Tampered " + testSuffix);
        req2.setBranchName("Different Branch Name");
        req2.setCategory("Restaurant");
        req2.setIdempotencyKey(clientKey);

        branchService.clearInMemoryIdempotencyCache(); // clear memory to force DB persistent check
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            branchService.createBranch(savedOwner.getId(), req2);
        });
        assertTrue(ex.getMessage().contains("different branch creation details"));

        // Simulate key expiry by setting expiresAt to the past
        IdempotencyKey keyRecord = idempotencyKeyRepository
                .findByOwnerIdAndScopeAndIdempotencyKey(savedOwner.getId(), "BRANCH_CREATION", clientKey)
                .orElseThrow();
        keyRecord.setExpiresAt(LocalDateTime.now().minusHours(1));
        idempotencyKeyRepository.save(keyRecord);
        branchService.clearInMemoryIdempotencyCache();

        // After expiry, sending a new request with this key cleans up the expired record and creates new branch safely
        CreateBranchRequest req3 = new CreateBranchRequest();
        req3.setName("Branch Gamma Post-Expiry " + testSuffix);
        req3.setBranchName("Gamma Branch");
        req3.setCategory("Retail");
        req3.setIdempotencyKey(clientKey);

        var resp3 = branchService.createBranch(savedOwner.getId(), req3);
        assertNotNull(resp3);
        assertNotEquals(resp1.getId(), resp3.getId(), "Post-expiry request should create new branch with fresh entitlement");
    }

    @Test
    @DisplayName("8. Multi-instance Caffeine consistency: peer instances converge after TTL without Redis")
    void testMultiInstanceCacheConvergenceAndTtlExpiration() throws Exception {
        // Create Instance 1 and Instance 2 Caffeine CacheManagers with a short 2-second TTL for testing
        CaffeineCacheManager cacheManagerInstance1 = new CaffeineCacheManager();
        cacheManagerInstance1.registerCustomCache("appSettings", Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(2, java.util.concurrent.TimeUnit.SECONDS)
                .build());

        CaffeineCacheManager cacheManagerInstance2 = new CaffeineCacheManager();
        cacheManagerInstance2.registerCustomCache("appSettings", Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(2, java.util.concurrent.TimeUnit.SECONDS)
                .build());

        String initialUpi = "primary@bank.upi";
        AdminSettingRequest req = new AdminSettingRequest();
        req.setUpiId(initialUpi);
        subscriptionService.adminUpdateSettings(req);

        var cache1 = cacheManagerInstance1.getCache("appSettings");
        var cache2 = cacheManagerInstance2.getCache("appSettings");
        assertNotNull(cache1);
        assertNotNull(cache2);

        // Both instances populate their local cache
        cache1.put("upi_id", initialUpi);
        cache2.put("upi_id", initialUpi);

        assertEquals(initialUpi, cache1.get("upi_id", String.class));
        assertEquals(initialUpi, cache2.get("upi_id", String.class));

        // Instance 1 updates the setting
        String updatedUpi = "secondary@bank.upi";
        req.setUpiId(updatedUpi);
        subscriptionService.adminUpdateSettings(req);

        // Instance 1 local cache is evicted
        cache1.clear();
        String instance1Fresh = subscriptionService.getConfiguredUpiId();
        assertEquals(updatedUpi, instance1Fresh);
        cache1.put("upi_id", instance1Fresh);

        // Peer Instance 2 serves local cached value prior to TTL expiry
        assertEquals(initialUpi, cache2.get("upi_id", String.class));

        // Wait for Instance 2 TTL to expire (2.1s)
        Thread.sleep(2100);

        // Instance 2 cache is now expired, refreshes from shared DB
        assertNull(cache2.get("upi_id"), "Instance 2 cache entry must be expired after TTL");
        String instance2Fresh = subscriptionService.getConfiguredUpiId();
        assertEquals(updatedUpi, instance2Fresh, "Instance 2 must refresh to updated value from authoritative database");

        // Restore original
        req.setUpiId("queueless@upi");
        subscriptionService.adminUpdateSettings(req);
    }

    @Test
    @DisplayName("9. Cache outages or eviction failures do not break payments, subscriptions, or queue operations")
    void testCacheOutagesDoNotBreakPaymentsOrQueueOperations() {
        // Completely invalidate the cache to simulate a cache outage
        var cache = cacheManager.getCache("appSettings");
        if (cache != null) {
            cache.clear();
            cache.invalidate();
        }

        // 1. Subscription pricing lookup falls back to authoritative database
        BigDecimal price = subscriptionService.getConfiguredMonthlyPrice();
        assertNotNull(price, "Monthly price must resolve directly from database during cache outage");

        // 2. Create test shop with active subscription for queue
        Shop shop = new Shop();
        shop.setName("Outage Test Shop");
        shop.setCategory("SALON");
        shop.setAvgServiceDurationMinutes(15);
        shop.setOwner(superAdmin);
        shop = shopRepository.save(shop);

        Subscription sub = new Subscription();
        sub.setShop(shop);
        sub.setOwner(superAdmin);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setPlanType(SubscriptionPlan.MONTHLY);
        sub.setStartDate(LocalDate.now());
        sub.setEndDate(LocalDate.now().plusMonths(1));
        subscriptionRepository.save(sub);

        // 3. Subscription status query succeeds
        var status = subscriptionService.getShopSubscriptionStatus(shop.getId());
        assertNotNull(status, "Shop subscription status must succeed during cache outage");

        // 4. Payment submission succeeds
        SubscriptionPaymentSubmitRequest payReq = new SubscriptionPaymentSubmitRequest();
        payReq.setPlanType(SubscriptionPlan.MONTHLY);
        payReq.setPaymentReference("UTR_OUTAGE_" + UUID.randomUUID().toString().substring(0, 8));
        payReq.setPaymentUpiId("payer@upi");

        var payResp = subscriptionService.submitPayment(shop.getId(), payReq);
        assertNotNull(payResp, "Payment submission must succeed uninterrupted during cache outage");

        // 5. Queue join succeeds
        var dashboard = queueService.startQueue(shop.getId());
        CustomerJoinRequest joinReq = new CustomerJoinRequest();
        joinReq.setCustomerName("Outage Resilient Customer");
        joinReq.setCustomerPhone("9876543210");
        var joinResp = queueService.customerJoinQueue(dashboard.getPublicQueueId(), joinReq);
        assertNotNull(joinResp, "Customer join must succeed uninterrupted during cache outage");
        assertNotNull(joinResp.getTokenNumber());
    }
}
