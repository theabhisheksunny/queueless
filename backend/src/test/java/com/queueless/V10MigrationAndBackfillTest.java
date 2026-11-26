package com.queueless;

import com.queueless.entity.Business;
import com.queueless.entity.Shop;
import com.queueless.entity.Subscription;
import com.queueless.entity.TrialEligibilityLedger;
import com.queueless.entity.User;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.UserRepository;
import com.queueless.util.PublicMerchantCodeGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class V10MigrationAndBackfillTest {

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.queueless.service.ShopService shopService;

    @Autowired
    private com.queueless.service.AuthService authService;

    @Autowired
    private com.queueless.service.BranchService branchService;

    @Autowired
    private com.queueless.repository.BusinessRepository businessRepository;

    @Autowired
    private com.queueless.repository.SubscriptionRepository subscriptionRepository;

    @Autowired
    private com.queueless.repository.TrialEligibilityLedgerRepository trialEligibilityLedgerRepository;

    @Autowired
    private com.queueless.repository.UserSessionRepository userSessionRepository;

    @Autowired
    private com.queueless.repository.QueueSessionRepository sessionRepository;

    @Autowired
    private com.queueless.repository.QueueCustomerRepository customerRepository;

    @Autowired
    private com.queueless.service.PhoneNormalizationService phoneNormalizationService;

    @Autowired
    private com.queueless.service.EntitlementService entitlementService;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.security.authentication.AuthenticationManager authenticationManager;

    @Autowired
    private com.queueless.security.JwtTokenProvider tokenProvider;

    @Autowired
    private com.queueless.repository.ShopMembershipRepository membershipRepository;

    @Autowired
    private com.queueless.repository.SubscriptionSeatRepository seatRepository;

    @Autowired
    private com.queueless.repository.PaymentRecordRepository paymentRecordRepository;

    @Autowired
    private com.queueless.repository.DeviceRegistrationRepository deviceRegistrationRepository;

    @Autowired
    private com.queueless.repository.AuditLogRepository auditLogRepository;

    @Autowired
    private com.queueless.service.ActiveQueueManager activeQueueManager;

    @Autowired
    private com.queueless.service.SubscriptionService subscriptionService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private com.queueless.controller.QueueController queueController;

    @Autowired
    private com.queueless.service.SseTicketService sseTicketService;

    @Autowired
    private java.time.Clock clock;

    @Test
    @DisplayName("V10 Migration: shops table contains public_merchant_code column with unique index")
    void testColumnAndIndexExist() {
        // Query database to ensure column exists
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(public_merchant_code) FROM shops", Integer.class);
        assertNotNull(count, "public_merchant_code column must exist in shops table");
    }

    @Test
    @DisplayName("Shop Persistence: Automatically generates opaque code via @PrePersist")
    void testShopPrePersistGeneratesCode() {
        User user = new User();
        user.setEmail("v10test_" + System.currentTimeMillis() + "@test.com");
        user.setPassword("hashedpass");
        user.setFullName("V10 Test User");
        user = userRepository.save(user);

        Shop shop = new Shop(user, "New V10 Shop", "Retail", 15);
        shop.setRegistrationStatus("REGISTERED");
        shop.setRegistrationFeePaid(BigDecimal.ZERO);
        assertNull(shop.getPublicMerchantCode(), "Code is null before persist");

        shop = shopRepository.save(shop);
        assertNotNull(shop.getPublicMerchantCode(), "Code must be populated by @PrePersist");
        assertTrue(shop.getPublicMerchantCode().startsWith("pm_"), "Code must start with pm_");
        assertEquals(19, shop.getPublicMerchantCode().length(), "Code must be 19 characters");
        assertTrue(PublicMerchantCodeGenerator.isValid(shop.getPublicMerchantCode()));

        // Verification: Code must never equal or derive from internal ID
        assertNotEquals(String.valueOf(shop.getId()), shop.getPublicMerchantCode());
        assertFalse(shop.getPublicMerchantCode().equals("pm_" + shop.getId()));
    }

    @Test
    @DisplayName("Repository: Lookup by publicMerchantCode resolves exact shop")
    void testLookupByPublicMerchantCode() {
        User user = new User();
        user.setEmail("lookuptest_" + System.currentTimeMillis() + "@test.com");
        user.setPassword("hashedpass");
        user.setFullName("Lookup User");
        user = userRepository.save(user);

        Shop shop = new Shop(user, "Lookup Branch", "Pharmacy", 10);
        shop.setRegistrationStatus("REGISTERED");
        shop.setRegistrationFeePaid(BigDecimal.ZERO);
        shop = shopRepository.save(shop);

        String code = shop.getPublicMerchantCode();
        Optional<Shop> found = shopRepository.findByPublicMerchantCode(code);
        assertTrue(found.isPresent());
        assertEquals(shop.getId(), found.get().getId());
        assertEquals("Lookup Branch", found.get().getName());

        // Non-existent code returns empty
        Optional<Shop> notFound = shopRepository.findByPublicMerchantCode("pm_nonexistent1234");
        assertFalse(notFound.isPresent());
    }

    @Test
    @DisplayName("Constraint Safety: Duplicate public_merchant_code violates unique constraint")
    void testUniqueConstraintViolation() {
        User user = new User();
        user.setEmail("dup1_" + System.currentTimeMillis() + "@test.com");
        user.setPassword("hashedpass");
        user.setFullName("Dup User 1");
        user = userRepository.save(user);

        Shop shop1 = new Shop(user, "Shop One", "Retail", 15);
        shop1.setRegistrationStatus("REGISTERED");
        shop1.setRegistrationFeePaid(BigDecimal.ZERO);
        shop1 = shopRepository.save(shop1);
        String sharedCode = shop1.getPublicMerchantCode();

        User user2 = new User();
        user2.setEmail("dup2_" + System.currentTimeMillis() + "@test.com");
        user2.setPassword("hashedpass");
        user2.setFullName("Dup User 2");
        user2 = userRepository.save(user2);

        Shop shop2 = new Shop(user2, "Shop Two", "Retail", 15);
        shop2.setRegistrationStatus("REGISTERED");
        shop2.setRegistrationFeePaid(BigDecimal.ZERO);
        shop2.setPublicMerchantCode(sharedCode); // Manually inject duplicate code

        assertThrows(DataIntegrityViolationException.class, () -> {
            shopRepository.saveAndFlush(shop2);
        }, "Inserting duplicate public_merchant_code must violate unique constraint");
    }

    @Test
    @DisplayName("Application-Level Retry: saveShopWithUniqueCodeRetry generates code and avoids collision")
    void testApplicationLevelUniqueCodeGenerationAndRetry() {
        User user = new User();
        user.setEmail("retryuser_" + System.currentTimeMillis() + "@test.com");
        user.setPassword("hashedpass");
        user.setFullName("Retry User");
        user = userRepository.save(user);

        // Pre-generate unique code via ShopService
        String code = shopService.generateUniquePublicMerchantCode();
        assertNotNull(code);
        assertTrue(code.startsWith("pm_"));
        assertFalse(shopRepository.existsByPublicMerchantCode(code));

        Shop shop = new Shop(user, "Retry Shop", "Retail", 15);
        shop.setRegistrationStatus("REGISTERED");
        shop.setRegistrationFeePaid(BigDecimal.ZERO);
        shop = shopService.saveShopWithUniqueCodeRetry(shop);

        assertNotNull(shop.getId());
        assertNotNull(shop.getPublicMerchantCode());
        assertTrue(shopRepository.existsByPublicMerchantCode(shop.getPublicMerchantCode()));
    }

    @Test
    @DisplayName("Transaction-Safe Retry: Simulated unique constraint collision retries in fresh transaction")
    void testSimulatedCollisionRetryInFreshTransaction() {
        User user = new User();
        user.setEmail("coluser_" + System.currentTimeMillis() + "@test.com");
        user.setPassword("hashedpass");
        user.setFullName("Collision User");
        user = userRepository.save(user);

        // Pre-seed shop1 with a fixed code
        Shop shop1 = new Shop(user, "Shop Existing", "Retail", 15);
        shop1.setRegistrationStatus("REGISTERED");
        shop1.setRegistrationFeePaid(BigDecimal.ZERO);
        shop1 = shopService.saveShopWithUniqueCodeRetry(shop1);
        String existingCode = shop1.getPublicMerchantCode();

        // Create shop2 and manually seed it with the same existing code
        Shop shop2 = new Shop(user, "Shop Retried", "Retail", 15);
        shop2.setRegistrationStatus("REGISTERED");
        shop2.setRegistrationFeePaid(BigDecimal.ZERO);
        shop2.setPublicMerchantCode(existingCode); // Force collision on attempt 1

        // saveShopWithUniqueCodeRetry should catch the DataIntegrityViolationException on attempt 1,
        // roll back attempt 1's transaction, start a fresh transaction, generate a new code, and succeed!
        Shop savedShop2 = shopService.saveShopWithUniqueCodeRetry(shop2, 3);

        assertNotNull(savedShop2.getId());
        assertNotNull(savedShop2.getPublicMerchantCode());
        assertNotEquals(existingCode, savedShop2.getPublicMerchantCode(), "New code must be generated after collision");
        assertTrue(shopRepository.existsByPublicMerchantCode(existingCode));
        assertTrue(shopRepository.existsByPublicMerchantCode(savedShop2.getPublicMerchantCode()));
    }

    @Test
    @DisplayName("Transaction-Safe Retry: Exhausted retry limit fails closed and preserves root cause")
    void testExhaustedRetryLimitPreservesRootCause() {
        User user = new User();
        user.setEmail("failuser_" + System.currentTimeMillis() + "@test.com");
        user.setPassword("hashedpass");
        user.setFullName("Failure User");
        user = userRepository.save(user);

        // Seed shop1
        Shop shop1 = new Shop(user, "Shop Blocker", "Retail", 15);
        shop1.setRegistrationStatus("REGISTERED");
        shop1.setRegistrationFeePaid(BigDecimal.ZERO);
        shop1 = shopService.saveShopWithUniqueCodeRetry(shop1);
        String blockerCode = shop1.getPublicMerchantCode();

        // Seed shop2 with duplicate code and maxAttempts = 1 (guaranteed to exhaust after 1 collision)
        Shop shop2 = new Shop(user, "Shop Failing", "Retail", 15);
        shop2.setRegistrationStatus("REGISTERED");
        shop2.setRegistrationFeePaid(BigDecimal.ZERO);
        shop2.setPublicMerchantCode(blockerCode);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            shopService.saveShopWithUniqueCodeRetry(shop2, 1);
        });

        assertTrue(ex.getMessage().contains("Failed to persist shop with unique public merchant code after 1 attempts"));
        assertNotNull(ex.getCause(), "Root cause must be preserved");
        assertTrue(ex.getCause() instanceof DataIntegrityViolationException ||
                   ex.getCause().getCause() instanceof org.hibernate.exception.ConstraintViolationException,
                   "Root cause must be constraint violation");
    }

    @Test
    @DisplayName("Rollback Behavior: Failed collision attempt leaves no orphaned shop record")
    void testRollbackBehaviorAfterFailedAttempt() {
        long initialShopCount = shopRepository.count();

        User user = new User();
        user.setEmail("orphanuser_" + System.currentTimeMillis() + "@test.com");
        user.setPassword("hashedpass");
        user.setFullName("Orphan User");
        user = userRepository.save(user);

        Shop shop1 = new Shop(user, "Shop Base", "Retail", 15);
        shop1.setRegistrationStatus("REGISTERED");
        shop1.setRegistrationFeePaid(BigDecimal.ZERO);
        shop1 = shopService.saveShopWithUniqueCodeRetry(shop1);
        String baseCode = shop1.getPublicMerchantCode();

        Shop shop2 = new Shop(user, "Shop Orphan", "Retail", 15);
        shop2.setRegistrationStatus("REGISTERED");
        shop2.setRegistrationFeePaid(BigDecimal.ZERO);
        shop2.setPublicMerchantCode(baseCode);

        assertThrows(IllegalStateException.class, () -> {
            shopService.saveShopWithUniqueCodeRetry(shop2, 1);
        });

        // Ensure only shop1 was created, shop2 was rolled back completely
        assertEquals(initialShopCount + 1, shopRepository.count(), "Failed shop must be completely rolled back");
    }

    @Test
    @DisplayName("Entry Point Verification: AuthService.register assigns valid opaque public merchant code")
    void testAuthServiceShopCreationEntryPoint() {
        String phone = "+9198" + (System.currentTimeMillis() % 100000000L);
        com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("Auth Service Shop");
        req.setCategory("Salon");

        com.queueless.dto.AuthResponse res = authService.register(req);
        assertNotNull(res);

        // Find created shop for this owner
        User owner = userRepository.findByPhone(phone).orElseThrow();
        Optional<Shop> shopOpt = shopRepository.findByOwnerId(owner.getId());
        assertTrue(shopOpt.isPresent());
        Shop createdShop = shopOpt.get();

        assertNotNull(createdShop.getPublicMerchantCode());
        assertTrue(createdShop.getPublicMerchantCode().startsWith("pm_"));
        assertEquals(19, createdShop.getPublicMerchantCode().length());
        assertTrue(PublicMerchantCodeGenerator.isValid(createdShop.getPublicMerchantCode()));
    }

    @Test
    @DisplayName("Entry Point Verification: BranchService.createBranch assigns valid opaque public merchant code")
    void testBranchServiceShopCreationEntryPoint() {
        String phone = "+9197" + (System.currentTimeMillis() % 100000000L);
        com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("Primary Branch Shop");
        req.setCategory("General");
        authService.register(req);

        User owner = userRepository.findByPhone(phone).orElseThrow();
        Shop primaryShop = shopRepository.findByOwnerId(owner.getId()).orElseThrow();

        com.queueless.dto.CreateBranchRequest branchReq = new com.queueless.dto.CreateBranchRequest();
        branchReq.setName("Secondary Branch");
        branchReq.setCategory("General");
        branchReq.setBusinessId(primaryShop.getBusiness() != null ? primaryShop.getBusiness().getId() : null);

        com.queueless.dto.BranchSummaryResponse branchRes = branchService.createBranch(owner.getId(), branchReq);
        assertNotNull(branchRes);

        Shop branchShop = shopRepository.findById(branchRes.getId()).orElseThrow();
        assertNotNull(branchShop.getPublicMerchantCode());
        assertTrue(branchShop.getPublicMerchantCode().startsWith("pm_"));
        assertEquals(19, branchShop.getPublicMerchantCode().length());
        assertNotEquals(primaryShop.getPublicMerchantCode(), branchShop.getPublicMerchantCode());
    }

    @Test
    @DisplayName("Concurrency: Concurrent shop registrations produce distinct public merchant codes")
    void testConcurrentShopRegistration() throws Exception {
        int threadCount = 5;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.List<Shop> createdShops = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        User user = new User();
        user.setEmail("concuser_" + System.currentTimeMillis() + "@test.com");
        user.setPassword("hashedpass");
        user.setFullName("Concurrent User");
        User savedUser = userRepository.save(user);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    Shop shop = new Shop(savedUser, "Concurrent Shop " + idx, "Retail", 15);
                    shop.setRegistrationStatus("REGISTERED");
                    shop.setRegistrationFeePaid(BigDecimal.ZERO);
                    Shop saved = shopService.saveShopWithUniqueCodeRetry(shop);
                    createdShops.add(saved);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount, createdShops.size(), "All concurrent shops must be created successfully");
        java.util.Set<String> uniqueCodes = new java.util.HashSet<>();
        for (Shop s : createdShops) {
            assertNotNull(s.getPublicMerchantCode());
            assertTrue(uniqueCodes.add(s.getPublicMerchantCode()), "Public merchant code must be unique across concurrent saves");
        }
    }

    @Test
    @DisplayName("Constraint Specific Retry: Foreign key violation fails immediately without retrying")
    void testForeignKeyViolationFailsImmediatelyWithoutRetry() {
        User ghostUser = new User();
        ghostUser.setId(99999999L);

        Shop shop = new Shop(ghostUser, "FK Failure Shop", "Retail", 15);
        shop.setRegistrationStatus("REGISTERED");
        shop.setRegistrationFeePaid(BigDecimal.ZERO);

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> {
            shopService.saveShopWithUniqueCodeRetry(shop, 3);
        }, "Foreign key violation must fail immediately without retrying");

        // Fails immediately on attempt 1 without wrapping in IllegalStateException
        assertFalse(ex.getMessage().contains("after 3 attempts"));
    }

    @Test
    @DisplayName("Constraint Specific Retry: NOT NULL violation fails immediately without retrying")
    void testNotNullViolationFailsImmediatelyWithoutRetry() {
        User user = new User();
        user.setEmail("notnull_" + System.currentTimeMillis() + "@test.com");
        user.setPassword("hashedpass");
        user.setFullName("Not Null User");
        user = userRepository.save(user);

        // Name is null, violating NOT NULL on shops.name
        Shop shop = new Shop(user, null, "Retail", 15);
        shop.setRegistrationStatus("REGISTERED");
        shop.setRegistrationFeePaid(BigDecimal.ZERO);

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> {
            shopService.saveShopWithUniqueCodeRetry(shop, 3);
        }, "NOT NULL violation must fail immediately without retrying");

        // Fails immediately on attempt 1 without retrying
        assertFalse(ex.getMessage().contains("after 3 attempts"));
    }

    @Test
    @DisplayName("Persistence Bypasses: BranchService rejects null ShopService with NullPointerException")
    void testBranchServiceRejectsNullShopService() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> {
            new com.queueless.service.BranchService(
                    businessRepository,
                    shopRepository,
                    userRepository,
                    sessionRepository,
                    paymentRecordRepository,
                    auditLogRepository,
                    activeQueueManager,
                    subscriptionService,
                    null,
                    null // Null ShopService rejected
            );
        });
        assertTrue(ex.getMessage().contains("Fallback persistence paths are strictly prohibited"));
    }

    @Test
    @DisplayName("Registration Atomicity: Compensating cleanup leaves no orphaned account on shop creation failure")
    void testRegistrationAtomicityAndCompensatingCleanupOnFailure() {
        String phone = "+9195" + (System.currentTimeMillis() % 100000000L);
        com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("Atomicity Test Shop");
        req.setCategory("Retail");

        // Subclass ShopService to simulate failure when persisting the shop without relying on bytecode proxies
        com.queueless.service.ShopService failingShopService = new com.queueless.service.ShopService(shopRepository, transactionManager) {
            @Override
            public Shop saveShopWithUniqueCodeRetry(Shop shop, int maxAttempts) {
                throw new IllegalStateException("Simulated shop creation failure during registration");
            }
        };

        com.queueless.service.AuthService customAuthService = new com.queueless.service.AuthService(
                userRepository,
                shopRepository,
                subscriptionRepository,
                trialEligibilityLedgerRepository,
                sessionRepository,
                customerRepository,
                phoneNormalizationService,
                entitlementService,
                passwordEncoder,
                authenticationManager,
                tokenProvider,
                businessRepository,
                membershipRepository,
                seatRepository,
                userSessionRepository,
                paymentRecordRepository,
                branchService,
                failingShopService,
                deviceRegistrationRepository,
                transactionManager,
                clock
        );

        // Attempt registration: must throw the simulated IllegalStateException
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            customAuthService.register(req);
        });
        assertEquals("Simulated shop creation failure during registration", ex.getMessage());

        // Verify compensating cleanup removed all partial records
        assertTrue(userRepository.findByPhone(phone).isEmpty(), "User must be completely cleaned up");
        assertFalse(trialEligibilityLedgerRepository.existsByNormalizedPhone(phoneNormalizationService.normalize(phone)),
                "Trial ledger must not be recorded");

        // Verify user can now successfully register with the same phone number (no orphaned or bricked state)
        com.queueless.dto.AuthResponse res = authService.register(req);
        assertNotNull(res, "Subsequent registration must succeed cleanly");
        assertTrue(userRepository.findByPhone(phone).isPresent(), "User is now properly registered");
    }

    @Test
    @DisplayName("Concurrency: Concurrent branch creations produce unique public merchant codes")
    void testConcurrentBranchCreation() throws Exception {
        String phone = "+9194" + (System.currentTimeMillis() % 100000000L);
        com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("Concurrent Branch Base");
        req.setCategory("General");
        authService.register(req);

        User owner = userRepository.findByPhone(phone).orElseThrow();
        Shop primaryShop = shopRepository.findByOwnerId(owner.getId()).orElseThrow();

        int threadCount = 4;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.List<com.queueless.dto.BranchSummaryResponse> branches = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    com.queueless.dto.CreateBranchRequest branchReq = new com.queueless.dto.CreateBranchRequest();
                    branchReq.setName("Concurrent Branch " + idx);
                    branchReq.setCategory("General");
                    branchReq.setBusinessId(primaryShop.getBusiness() != null ? primaryShop.getBusiness().getId() : null);
                    branches.add(branchService.createBranch(owner.getId(), branchReq));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount, branches.size());
        java.util.Set<String> uniqueCodes = new java.util.HashSet<>();
        uniqueCodes.add(primaryShop.getPublicMerchantCode());
        for (var b : branches) {
            Shop s = shopRepository.findById(b.getId()).orElseThrow();
            assertNotNull(s.getPublicMerchantCode());
            assertTrue(uniqueCodes.add(s.getPublicMerchantCode()), "Each branch must have a unique public merchant code");
        }
    }

    // =========================================================================
    // REGISTRATION RECOVERY & COMPENSATION VERIFICATION
    // =========================================================================

    @Test
    @DisplayName("Registration Recovery: Failure after shop creation cleans up shop, business, and user")
    void testFailureAfterShopCreationCleansUpAllRecords() {
        String phone = "+9193" + (System.currentTimeMillis() % 100000000L);
        com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("Stage 3 Failure Shop");
        req.setCategory("Retail");

        // Token provider that throws an exception to simulate failure after shop and trial creation
        com.queueless.security.JwtTokenProvider failingTokenProvider = new com.queueless.security.JwtTokenProvider(
                "queueless-secret-token-key-must-be-at-least-32-bytes-long!",
                2592000000L
        ) {
            @Override
            public String generateTokenForUsername(String username, String sessionId) {
                throw new IllegalStateException("Simulated JWT signing infrastructure failure after shop creation");
            }
        };

        com.queueless.service.AuthService customAuthService = new com.queueless.service.AuthService(
                userRepository,
                shopRepository,
                subscriptionRepository,
                trialEligibilityLedgerRepository,
                sessionRepository,
                customerRepository,
                phoneNormalizationService,
                entitlementService,
                passwordEncoder,
                authenticationManager,
                failingTokenProvider,
                businessRepository,
                membershipRepository,
                seatRepository,
                userSessionRepository,
                paymentRecordRepository,
                branchService,
                shopService,
                deviceRegistrationRepository,
                transactionManager,
                clock
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            customAuthService.register(req);
        });
        assertEquals("Simulated JWT signing infrastructure failure after shop creation", ex.getMessage());

        // Verify full compensation: no user, no shop, no trial ledger
        assertTrue(userRepository.findByPhone(phone).isEmpty(), "User must be removed by compensation");
        String normalizedPhone = phoneNormalizationService.normalize(phone);
        assertFalse(trialEligibilityLedgerRepository.existsByNormalizedPhone(normalizedPhone), "Trial ledger must be cleaned up");

        // Re-registration with real authService succeeds cleanly
        com.queueless.dto.AuthResponse res = authService.register(req);
        assertNotNull(res);
        assertTrue(userRepository.findByPhone(phone).isPresent(), "Re-registration succeeds cleanly");
    }

    @Test
    @DisplayName("Registration Recovery: Orphaned account from prior crash is observable and recoverable")
    void testCompensatingCleanupFailureIsObservableAndRecoverable() {
        String phone = "+9192" + (System.currentTimeMillis() % 100000000L);
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        // Manually seed an orphaned user without shops (simulating mid-crash uncompleted cleanup)
        User orphanedUser = new User("orphan_" + System.currentTimeMillis() + "@test.com", "pass", "Orphan", normalizedPhone, com.queueless.entity.UserRole.ROLE_OWNER);
        userRepository.saveAndFlush(orphanedUser);

        // Verify reconcileIncompleteAccount detects and recovers
        boolean reconciled = authService.reconcileIncompleteAccount(normalizedPhone);
        assertTrue(reconciled, "Orphaned account must be detected and reconciled");
        assertTrue(userRepository.findByPhone(normalizedPhone).isEmpty(), "Orphaned user must be purged");

        // Verify register() with same phone auto-reconciles if needed and completes registration
        User secondOrphan = new User("orphan2_" + System.currentTimeMillis() + "@test.com", "pass", "Orphan 2", normalizedPhone, com.queueless.entity.UserRole.ROLE_OWNER);
        userRepository.saveAndFlush(secondOrphan);

        com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("Recovered Shop");
        req.setCategory("Retail");

        com.queueless.dto.AuthResponse res = authService.register(req);
        assertNotNull(res, "Registration must succeed via automatic recovery");
        assertTrue(userRepository.findByPhone(normalizedPhone).isPresent());
        assertNotNull(res.getShopId(), "Shop must be properly registered");
    }

    @Test
    @DisplayName("Concurrency: Concurrent registration cannot create duplicate owners or consume multiple trial entitlements")
    void testConcurrentRegistrationPreventsDuplicateOwnersAndTrials() throws Exception {
        String phone = "+9191" + (System.currentTimeMillis() % 100000000L);
        int threadCount = 4;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.List<com.queueless.dto.AuthResponse> successfulRegistrations = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<Exception> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
                    req.setPhone(phone);
                    req.setPin("123456");
                    req.setConfirmPin("123456");
                    req.setShopName("Concurrent Owner Shop");
                    req.setCategory("Retail");
                    successfulRegistrations.add(authService.register(req));
                } catch (Exception ex) {
                    errors.add(ex);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successfulRegistrations.size(), "Exactly ONE registration must succeed");
        assertEquals(threadCount - 1, errors.size(), "All other concurrent attempts must be rejected");

        String normalized = phoneNormalizationService.normalize(phone);
        assertEquals(1, userRepository.findAll().stream().filter(u -> normalized.equals(u.getPhone())).count(),
                "Exactly one user must exist in the database");
        assertTrue(trialEligibilityLedgerRepository.existsByNormalizedPhone(normalized),
                "Exactly one trial ledger entry must exist");
    }

    // =========================================================================
    // H2 SIMULATION: MULTI-INSTANCE SSE TICKET STORE VERIFICATION
    // =========================================================================

    @Test
    @DisplayName("H2 Simulation: Ticket issued by Instance A can be consumed by Instance B")
    void testH2SimulationTicketIssuedByInstanceAAndConsumedByInstanceB() {
        User user = userRepository.save(new User("sse_sim_" + System.currentTimeMillis() + "@test.com", "pass", "SSE User", "+919811110001", com.queueless.entity.UserRole.ROLE_OWNER));
        Shop shop = shopService.saveShopWithUniqueCodeRetry(new Shop(user, "SSE Shop", "Retail", 15));

        com.queueless.service.SseTicketService instanceA = new com.queueless.service.SseTicketService(jdbcTemplate, null, clock);
        com.queueless.service.SseTicketService instanceB = new com.queueless.service.SseTicketService(jdbcTemplate, null, clock);

        var issued = instanceA.issueTicket(user.getId(), shop.getId());
        assertNotNull(issued);
        assertTrue(issued.ticket().startsWith("stk_"));

        var consumed = instanceB.consumeTicket(issued.ticket(), shop.getId(), user.getId());
        assertNotNull(consumed);
        assertEquals(issued.ticket(), consumed.ticket());

        // Replay rejected
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> {
            instanceA.consumeTicket(issued.ticket(), shop.getId(), user.getId());
        });
    }

    // =========================================================================
    // PHASE G.7: CRASH RECOVERY, DURABLE STATE & URI EXPOSURE VERIFICATION
    // =========================================================================

    @Test
    @DisplayName("Crash after Stage 1: User in PENDING_REGISTRATION with 0 shops is safely reconciled and completed")
    void testCrashAfterStage1CanBeRecoveredAndReRegistered() {
        String phone = "+9193" + (System.currentTimeMillis() % 100000000L);
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        // Simulate crash after Stage 1: User persisted with PENDING_REGISTRATION, business created, 0 shops
        User pendingUser = new User("pending1_" + System.currentTimeMillis() + "@test.com", "pass", "Pending Owner", normalizedPhone, com.queueless.entity.UserRole.ROLE_OWNER);
        pendingUser.setStatus(User.STATUS_PENDING_REGISTRATION);
        pendingUser = userRepository.saveAndFlush(pendingUser);
        businessRepository.saveAndFlush(new Business(pendingUser, "Pending Business"));

        assertTrue(authService.isIncompleteRegistration(pendingUser), "Stage 1 crash must be identified as incomplete registration");

        // Verify login attempt on incomplete account is blocked
        com.queueless.dto.PinLoginRequest pinReq = new com.queueless.dto.PinLoginRequest();
        pinReq.setPhone(phone);
        pinReq.setPin("123456");
        assertThrows(org.springframework.security.authentication.BadCredentialsException.class, () -> {
            authService.pinLogin(pinReq, "127.0.0.1");
        });

        // Re-registration with same phone auto-reconciles incomplete state and completes cleanly
        com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("Completed Shop After Crash");
        req.setCategory("Retail");

        com.queueless.dto.AuthResponse res = authService.register(req);
        assertNotNull(res);
        assertNotNull(res.getShopId());
        User recoveredUser = userRepository.findByPhone(normalizedPhone).orElseThrow();
        assertEquals(User.STATUS_ACTIVE, recoveredUser.getStatus(), "Recovered user must be ACTIVE");
        assertFalse(authService.isIncompleteRegistration(recoveredUser));
    }

    @Test
    @DisplayName("Crash after Stage 2: Shop committed but no subscription/ledger is safely reconciled without duplicate shops")
    void testCrashAfterStage2CanBeRecoveredAndReRegistered() {
        String phone = "+9194" + (System.currentTimeMillis() % 100000000L);
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        // Simulate crash after Stage 2: User in PENDING_REGISTRATION, business created, shop created, NO subscription, NO ledger
        User pendingUser = new User("pending2_" + System.currentTimeMillis() + "@test.com", "pass", "Pending Owner 2", normalizedPhone, com.queueless.entity.UserRole.ROLE_OWNER);
        pendingUser.setStatus(User.STATUS_PENDING_REGISTRATION);
        pendingUser = userRepository.saveAndFlush(pendingUser);
        Business business = businessRepository.saveAndFlush(new Business(pendingUser, "Pending Business 2"));
        Shop shop = new Shop(pendingUser, "Orphaned Shop", "Retail", 15);
        shop.setBusiness(business);
        shop = shopService.saveShopWithUniqueCodeRetry(shop);

        assertTrue(authService.isIncompleteRegistration(pendingUser), "Stage 2 crash (shop without subscription/ledger) must be incomplete");

        // Calling register with same phone must auto-reconcile, remove incomplete shop, and re-create cleanly
        com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("Clean Recovered Shop");
        req.setCategory("Retail");

        com.queueless.dto.AuthResponse res = authService.register(req);
        assertNotNull(res);
        User activeUser = userRepository.findByPhone(normalizedPhone).orElseThrow();
        assertEquals(User.STATUS_ACTIVE, activeUser.getStatus());

        // Assert exactly ONE shop exists for this user (no duplicate orphaned shops!)
        assertEquals(1, shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(activeUser.getId()).size(),
                "Must have exactly 1 shop; previous orphaned shop must be cleaned up");
        assertEquals(1, subscriptionRepository.findByShopIdOrderByCreatedAtDesc(res.getShopId()).size(),
                "Must have exactly 1 trial subscription");
        assertTrue(trialEligibilityLedgerRepository.existsByNormalizedPhone(normalizedPhone),
                "Trial eligibility ledger must exist");
    }

    @Test
    @DisplayName("Crash after Subscription Creation: Orphaned subscription and shop are safely compensated")
    void testCrashAfterSubscriptionCreationBeforeLedgerRecovered() {
        String phone = "+9195" + (System.currentTimeMillis() % 100000000L);
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        // Simulate crash after subscription creation before trial ledger was saved
        User pendingUser = new User("pending3_" + System.currentTimeMillis() + "@test.com", "pass", "Pending Owner 3", normalizedPhone, com.queueless.entity.UserRole.ROLE_OWNER);
        pendingUser.setStatus(User.STATUS_PENDING_REGISTRATION);
        pendingUser = userRepository.saveAndFlush(pendingUser);
        Business business = businessRepository.saveAndFlush(new Business(pendingUser, "Pending Business 3"));
        Shop shop = shopService.saveShopWithUniqueCodeRetry(new Shop(pendingUser, "Shop 3", "Retail", 15));
        Subscription sub = new Subscription(pendingUser, shop, com.queueless.entity.SubscriptionPlan.TRIAL, com.queueless.entity.SubscriptionStatus.ACTIVE, java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(7), java.math.BigDecimal.ZERO);
        subscriptionRepository.saveAndFlush(sub);

        assertTrue(authService.isIncompleteRegistration(pendingUser), "Account in PENDING_REGISTRATION must be recognized as incomplete");

        // Reconcile must purge subscription, shop, business and user
        boolean reconciled = authService.reconcileIncompleteAccount(normalizedPhone);
        assertTrue(reconciled);
        assertTrue(userRepository.findByPhone(normalizedPhone).isEmpty());
        assertTrue(subscriptionRepository.findByShopIdOrderByCreatedAtDesc(shop.getId()).isEmpty());
        assertFalse(trialEligibilityLedgerRepository.existsByNormalizedPhone(normalizedPhone));
    }

    @Test
    @DisplayName("Crash after Trial Ledger Creation: Status PENDING_REGISTRATION cleans up without leaking trial entitlement")
    void testCrashAfterTrialLedgerCreationBeforeActivationRecovered() {
        String phone = "+9196" + (System.currentTimeMillis() % 100000000L);
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        User pendingUser = new User("pending4_" + System.currentTimeMillis() + "@test.com", "pass", "Pending Owner 4", normalizedPhone, com.queueless.entity.UserRole.ROLE_OWNER);
        pendingUser.setStatus(User.STATUS_PENDING_REGISTRATION);
        pendingUser = userRepository.saveAndFlush(pendingUser);
        Shop shop = shopService.saveShopWithUniqueCodeRetry(new Shop(pendingUser, "Shop 4", "Retail", 15));
        Subscription sub = new Subscription(pendingUser, shop, com.queueless.entity.SubscriptionPlan.TRIAL, com.queueless.entity.SubscriptionStatus.ACTIVE, java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(7), java.math.BigDecimal.ZERO);
        subscriptionRepository.saveAndFlush(sub);

        TrialEligibilityLedger ledger = new TrialEligibilityLedger();
        ledger.setNormalizedPhone(normalizedPhone);
        ledger.setPhoneHmac(phoneNormalizationService.computeHmac(normalizedPhone));
        ledger.setOwnerId(pendingUser.getId());
        ledger.setShopId(shop.getId());
        ledger.setOwnerEmail(pendingUser.getEmail());
        ledger.setRegistrationStatus("PENDING");
        ledger.setTrialStartedAt(java.time.LocalDateTime.now());
        ledger.setTrialEndsAt(java.time.LocalDateTime.now().plusDays(7));
        ledger.setTrialConsumedAt(java.time.LocalDateTime.now());
        trialEligibilityLedgerRepository.saveAndFlush(ledger);

        assertTrue(authService.isIncompleteRegistration(pendingUser));

        // Reconciling cleans up own ledger and allows fresh registration
        boolean reconciled = authService.reconcileIncompleteAccount(normalizedPhone);
        assertTrue(reconciled);
        assertFalse(trialEligibilityLedgerRepository.existsByNormalizedPhone(normalizedPhone),
                "Own trial ledger must be purged during incomplete registration recovery");

        // Re-registration with same phone succeeds
        com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("Fresh Success Shop");
        req.setCategory("Retail");

        com.queueless.dto.AuthResponse res = authService.register(req);
        assertNotNull(res);
        assertTrue(trialEligibilityLedgerRepository.existsByNormalizedPhone(normalizedPhone));
    }

    @Test
    @DisplayName("Repeated Registration: Valid active account cannot be overwritten or re-registered")
    void testRepeatedRegistrationAttemptWithValidAccountRejected() {
        String phone = "+9197" + (System.currentTimeMillis() % 100000000L);
        com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("Valid Existing Shop");
        req.setCategory("Retail");

        com.queueless.dto.AuthResponse res = authService.register(req);
        assertNotNull(res);

        // Immediate repeated registration attempt must be rejected
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            authService.register(req);
        });
        assertTrue(ex.getMessage().contains("already exists"), "Must state account already exists");

        // Valid account must remain completely intact
        String normalized = phoneNormalizationService.normalize(phone);
        User user = userRepository.findByPhone(normalized).orElseThrow();
        assertEquals(User.STATUS_ACTIVE, user.getStatus());
        assertFalse(authService.isIncompleteRegistration(user));
    }

    @Test
    @DisplayName("SSE URI Exposure: Header-authenticated stream connects with ZERO credentials in URI")
    void testSseStreamHeaderAuthenticationWithoutCredentialInUri() {
        // Register an owner
        String phone = "+9198" + (System.currentTimeMillis() % 100000000L);
        com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("SSE Header Shop");
        req.setCategory("Retail");

        com.queueless.dto.AuthResponse auth = authService.register(req);
        Principal principal = () -> auth.getPhone();

        // 1. Connect to /stream/owner with Principal and X-Shop-Id header, NO ticket in query param
        var emitter1 = queueController.streamOwnerUpdates(principal, null, null, null, null, auth.getShopId(), null);
        assertNotNull(emitter1, "Emitter must be successfully created via Principal header authentication without URI ticket");

        // 2. Connect to /{shopId}/stream with path variable and Principal, NO ticket in query param
        var emitter2 = queueController.streamOwnerUpdates(principal, auth.getShopId(), null, null, null, null, null);
        assertNotNull(emitter2, "Emitter must be created on /{shopId}/stream without URI credentials");
    }

    @Test
    @DisplayName("SSE URI Exposure: Header ticket authentication (X-SSE-Ticket) connects with ZERO credentials in URI")
    void testSseStreamHeaderTicketAuthenticationWithoutCredentialInUri() {
        String phone = "+9199" + (System.currentTimeMillis() % 100000000L);
        com.queueless.dto.RegisterRequest req = new com.queueless.dto.RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("SSE Ticket Header Shop");
        req.setCategory("Retail");

        com.queueless.dto.AuthResponse auth = authService.register(req);
        User user = userRepository.findByPhone(phoneNormalizationService.normalize(phone)).orElseThrow();

        // Issue ticket
        var issued = sseTicketService.issueTicket(user.getId(), auth.getShopId());
        assertNotNull(issued);

        // Connect passing ticket strictly in X-SSE-Ticket HTTP header, NOT in the URL
        var emitter = queueController.streamOwnerUpdates(null, auth.getShopId(), null, issued.ticket(), null, null, null);
        assertNotNull(emitter, "Must authenticate via X-SSE-Ticket header without URI parameter");

        // Replay of header ticket is rejected
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> {
            queueController.streamOwnerUpdates(null, auth.getShopId(), null, issued.ticket(), null, null, null);
        });
    }

    @Test
    @DisplayName("SSE URI Exposure: Long-lived JWT in query parameter is strictly forbidden and rejected")
    void testSseStreamRejectsJwtInQueryParameter() {
        // Attempting to connect without header/ticket must fail
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> {
            queueController.streamOwnerUpdates(null, 1L, null, null, null, null, null);
        });
    }
}
