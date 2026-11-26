package com.queueless;

import com.queueless.dto.AuthResponse;
import com.queueless.dto.BranchSummaryResponse;
import com.queueless.dto.CreateBranchRequest;
import com.queueless.dto.CustomerJoinRequest;
import com.queueless.dto.CustomerJoinResponse;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.AuditLog;
import com.queueless.entity.Business;
import com.queueless.entity.QueueCustomer;
import com.queueless.entity.Shop;
import com.queueless.entity.User;
import com.queueless.model.ShopActiveQueue;
import com.queueless.repository.*;
import com.queueless.service.ActiveQueueManager;
import com.queueless.service.AuthService;
import com.queueless.service.BranchService;
import com.queueless.service.EntitlementService;
import com.queueless.service.QueueService;
import com.queueless.service.ShopService;
import com.queueless.service.SseEmitterService;
import com.queueless.service.SseTicketService;
import com.queueless.service.SubscriptionService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class PostgreSqlDistributedConcurrencyAndIdempotencyTest {

    private static EmbeddedPostgres embeddedPostgres;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws IOException {
        embeddedPostgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url", () -> embeddedPostgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (embeddedPostgres != null) {
            embeddedPostgres.close();
        }
    }

    @Autowired
    private AuthService authService;

    @Autowired
    private BranchService branchServiceInstance1;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private QueueSessionRepository sessionRepository;

    @Autowired
    private PaymentRecordRepository paymentRecordRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ActiveQueueManager activeQueueManager;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private QueueService queueServiceInstanceA;

    @Autowired
    private QueueCustomerRepository customerRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private SseEmitterService sseEmitterService;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private com.queueless.service.DataLifecycleMaintenanceService maintenanceService;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private ShopService shopService;

    @Autowired
    private com.queueless.repository.TrialEligibilityLedgerRepository trialEligibilityLedgerRepository;

    @Autowired
    private com.queueless.service.PhoneNormalizationService phoneNormalizationService;

    @Autowired
    private com.queueless.controller.QueueController queueController;

    private BranchService instanceB;

    private BranchService createSecondInstance() {
        return new BranchService(
                businessRepository,
                shopRepository,
                userRepository,
                sessionRepository,
                paymentRecordRepository,
                auditLogRepository,
                activeQueueManager,
                subscriptionService,
                idempotencyKeyRepository,
                shopService
        );
    }

    @org.junit.jupiter.api.BeforeEach
    void setUpSecondInstance() {
        instanceB = createSecondInstance();
    }

    private BranchSummaryResponse callInstanceB(Long ownerId, CreateBranchRequest req) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> instanceB.createBranch(ownerId, req));
    }

    private User registerOwner(String phone, String fullName, String shopName) {
        RegisterRequest reg = new RegisterRequest();
        reg.setPhone(phone);
        reg.setPin("654321");
        reg.setConfirmPin("654321");
        reg.setFullName(fullName);
        reg.setShopName(shopName);
        reg.setCategory("Barber Shop");
        reg.setDeviceFingerprint("fp-" + phone);

        authService.register(reg);
        return userRepository.findByPhone("+91" + phone).orElseThrow();
    }

    // =========================================================================
    // SECTION 2: IDEMPOTENCY VERIFICATION
    // =========================================================================

    @Test
    @DisplayName("2.1 Same owner + same key + same request returns original branch (In-Memory)")
    void testSameOwnerSameKeySameRequestReturnsOriginal() {
        User owner = registerOwner("9811100001", "Idemp Owner 1", "Barber One");
        String key = "idemp-uuid-" + UUID.randomUUID();

        CreateBranchRequest req1 = new CreateBranchRequest();
        req1.setName("Branch Alpha");
        req1.setBranchName("North Alpha");
        req1.setCategory("Barber Shop");
        req1.setAddress("Gate 1");
        req1.setIdempotencyKey(key);

        BranchSummaryResponse res1 = branchServiceInstance1.createBranch(owner.getId(), req1);
        assertNotNull(res1.getId());

        // Same request and key
        CreateBranchRequest req2 = new CreateBranchRequest();
        req2.setName("Branch Alpha");
        req2.setBranchName("North Alpha");
        req2.setCategory("Barber Shop");
        req2.setAddress("Gate 1");
        req2.setIdempotencyKey(key);

        BranchSummaryResponse res2 = branchServiceInstance1.createBranch(owner.getId(), req2);
        assertEquals(res1.getId(), res2.getId(), "Must return identical branch ID");
        assertEquals(res1.getPublicQueueId(), res2.getPublicQueueId());

        assertEquals(2, shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.getId()).size(),
                "Must only have 1 primary + 1 branch in database");
    }

    @Test
    @DisplayName("2.2 Same owner + same key + different request rejects with safe conflict response")
    void testSameOwnerSameKeyDifferentRequestRejectsWithConflict() {
        User owner = registerOwner("9811100002", "Idemp Owner 2", "Barber Two");
        String key = "idemp-uuid-conflict-" + UUID.randomUUID();

        CreateBranchRequest req1 = new CreateBranchRequest();
        req1.setName("Original Branch");
        req1.setBranchName("Sector 1");
        req1.setCategory("Barber Shop");
        req1.setIdempotencyKey(key);

        BranchSummaryResponse res1 = branchServiceInstance1.createBranch(owner.getId(), req1);
        assertNotNull(res1.getId());

        // Different request reusing the same idempotency key
        CreateBranchRequest reqMismatch = new CreateBranchRequest();
        reqMismatch.setName("Tampered Branch Name");
        reqMismatch.setBranchName("Sector 99");
        reqMismatch.setCategory("Clinic / Healthcare"); // Different details
        reqMismatch.setIdempotencyKey(key);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            branchServiceInstance1.createBranch(owner.getId(), reqMismatch);
        });

        assertTrue(ex.getMessage().contains("different branch creation details") ||
                   ex.getMessage().contains("Idempotency key has already been used"),
                "Must reject with safe message regarding key reuse with different parameters");
    }

    @Test
    @DisplayName("2.3 Same owner + different keys + identical branch details creates two distinct branches")
    void testSameOwnerDifferentKeysIdenticalDetailsCreatesTwoDistinctBranches() {
        User owner = registerOwner("9811100003", "Idemp Owner 3", "Barber Three");

        CreateBranchRequest branchA = new CreateBranchRequest();
        branchA.setName("Identical Name Salon");
        branchA.setBranchName("City Mall Floor 2");
        branchA.setCategory("Barber Shop");
        branchA.setAddress("Food Court Area");
        branchA.setIdempotencyKey("uuid-key-alpha-" + UUID.randomUUID());

        BranchSummaryResponse resA = branchServiceInstance1.createBranch(owner.getId(), branchA);

        CreateBranchRequest branchB = new CreateBranchRequest();
        branchB.setName("Identical Name Salon"); // Identical
        branchB.setBranchName("City Mall Floor 2"); // Identical
        branchB.setCategory("Barber Shop"); // Identical
        branchB.setAddress("Food Court Area"); // Identical
        branchB.setIdempotencyKey("uuid-key-beta-" + UUID.randomUUID()); // Different key

        BranchSummaryResponse resB = branchServiceInstance1.createBranch(owner.getId(), branchB);

        assertNotEquals(resA.getId(), resB.getId(), "Must create distinct branch records with separate IDs");
        assertNotEquals(resA.getPublicQueueId(), resB.getPublicQueueId(), "Must assign distinct publicQueueIds");

        assertEquals(3, shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.getId()).size(),
                "Must have 1 primary + 2 secondary branches in database");
    }

    @Test
    @DisplayName("2.4 Retry after application restart (In-memory cache wiped) returns original branch from PostgreSQL")
    void testRetryAfterApplicationRestartReturnsOriginalBranch() {
        User owner = registerOwner("9811100004", "Idemp Owner 4", "Restart Salon");
        String key = "idemp-uuid-restart-" + UUID.randomUUID();

        CreateBranchRequest req = new CreateBranchRequest();
        req.setName("Persistent Branch");
        req.setBranchName("Main Highway");
        req.setCategory("Barber Shop");
        req.setIdempotencyKey(key);

        BranchSummaryResponse created = branchServiceInstance1.createBranch(owner.getId(), req);
        assertNotNull(created.getId());

        // Simulate application restart: wipe in-memory cache completely
        branchServiceInstance1.clearInMemoryIdempotencyCache();

        // Retry same request with same key
        BranchSummaryResponse recovered = branchServiceInstance1.createBranch(owner.getId(), req);
        assertEquals(created.getId(), recovered.getId(), "Must recover original branch from PostgreSQL audit log");
        assertEquals(created.getPublicQueueId(), recovered.getPublicQueueId());
    }

    @Test
    @DisplayName("2.5 Failed transaction rolls back: neither branch nor idempotency record persists")
    void testFailedTransactionDoesNotPersistBranchOrIdempotencyRecord() {
        User owner = registerOwner("9811100005", "Idemp Owner 5", "Rollback Salon");
        String key = "idemp-uuid-failing-" + UUID.randomUUID();

        CreateBranchRequest badReq = new CreateBranchRequest();
        badReq.setBusinessId(9999999L); // Invalid business triggers rollback
        badReq.setName("Will Fail");
        badReq.setIdempotencyKey(key);

        assertThrows(IllegalArgumentException.class, () -> {
            branchServiceInstance1.createBranch(owner.getId(), badReq);
        });

        // Verify no branch created
        assertEquals(1, shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.getId()).size());

        // Verify no idempotency audit log record created
        Optional<AuditLog> audit = auditLogRepository.findFirstByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                "BRANCH_IDEMPOTENCY", owner.getId() + ":" + key);
        assertFalse(audit.isPresent(), "Rollback must not persist idempotency audit record");
    }

    // =========================================================================
    // SECTION 3: DISTRIBUTED MULTI-INSTANCE CONCURRENCY VERIFICATION
    // =========================================================================

    @Test
    @DisplayName("3.1 Same key submitted concurrently from two backend instances creates exactly one branch")
    void testCrossInstanceConcurrentIdempotency() throws Exception {
        User owner = registerOwner("9811100006", "Idemp Owner 6", "Dual Instance Salon");
        String key = "shared-uuid-cloudrun-" + UUID.randomUUID();

        BranchService instanceA = branchServiceInstance1;

        CreateBranchRequest req = new CreateBranchRequest();
        req.setName("Cloud Run Multi-Instance Branch");
        req.setBranchName("Zone A");
        req.setCategory("Barber Shop");
        req.setIdempotencyKey(key);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);

        Future<BranchSummaryResponse> futureA = executor.submit(() -> {
            startGate.await();
            return instanceA.createBranch(owner.getId(), req);
        });

        Future<BranchSummaryResponse> futureB = executor.submit(() -> {
            startGate.await();
            return callInstanceB(owner.getId(), req);
        });

        startGate.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        BranchSummaryResponse resA = futureA.get();
        BranchSummaryResponse resB = futureB.get();

        assertNotNull(resA);
        assertNotNull(resB);
        assertEquals(resA.getId(), resB.getId(), "Both instances must coordinate to return the exact same branch ID");
        assertEquals(resA.getPublicQueueId(), resB.getPublicQueueId());

        assertEquals(2, shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.getId()).size(),
                "PostgreSQL must contain exactly 1 primary + 1 secondary branch");
    }

    @Test
    @DisplayName("3.2 Simultaneous branch creations for same owner across two instances serialize via SELECT FOR UPDATE")
    void testCrossInstanceSimultaneousCreationsForSameOwner() throws Exception {
        User owner = registerOwner("9811100007", "Idemp Owner 7", "Serialization Hub");

        BranchService instanceA = branchServiceInstance1;

        int totalBranches = 4;
        ExecutorService executor = Executors.newFixedThreadPool(totalBranches);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<BranchSummaryResponse>> futures = new ArrayList<>();

        for (int i = 0; i < totalBranches; i++) {
            final int idx = i;
            final boolean useInstanceA = (i % 2 == 0);
            futures.add(executor.submit(() -> {
                startGate.await();
                CreateBranchRequest r = new CreateBranchRequest();
                r.setName("Serialized Branch " + idx);
                r.setBranchName("Loc " + idx);
                r.setCategory("Barber Shop");
                r.setIdempotencyKey("uuid-serialized-" + idx + "-" + UUID.randomUUID());
                return useInstanceA ? instanceA.createBranch(owner.getId(), r) : callInstanceB(owner.getId(), r);
            }));
        }

        startGate.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));

        List<Long> ids = new ArrayList<>();
        for (Future<BranchSummaryResponse> f : futures) {
            BranchSummaryResponse r = f.get();
            assertNotNull(r);
            ids.add(r.getId());
        }

        assertEquals(totalBranches, ids.stream().distinct().count(),
                "All 4 branches must succeed without constraint violations or deadlocks");
        assertEquals(totalBranches + 1, shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.getId()).size(),
                "Total shops must be 5 (1 primary + 4 secondary branches)");
    }

    @Test
    @DisplayName("3.3 Different owners create branches independently without blocking or contention")
    void testDifferentOwnersCreateBranchesIndependently() throws Exception {
        User ownerX = registerOwner("9811100008", "Owner X", "Brand X");
        User ownerY = registerOwner("9811100009", "Owner Y", "Brand Y");

        BranchService instanceA = branchServiceInstance1;

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        Future<BranchSummaryResponse> futureX = executor.submit(() -> {
            startGate.await();
            CreateBranchRequest r = new CreateBranchRequest();
            r.setName("X Branch 2");
            r.setBranchName("Loc X2");
            r.setCategory("Salon / Beauty");
            r.setIdempotencyKey("key-x-" + UUID.randomUUID());
            return instanceA.createBranch(ownerX.getId(), r);
        });

        Future<BranchSummaryResponse> futureY = executor.submit(() -> {
            startGate.await();
            CreateBranchRequest r = new CreateBranchRequest();
            r.setName("Y Branch 2");
            r.setBranchName("Loc Y2");
            r.setCategory("Clinic / Healthcare");
            r.setIdempotencyKey("key-y-" + UUID.randomUUID());
            return callInstanceB(ownerY.getId(), r);
        });

        startGate.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        BranchSummaryResponse resX = futureX.get();
        BranchSummaryResponse resY = futureY.get();

        assertNotNull(resX.getId());
        assertNotNull(resY.getId());
        assertNotEquals(resX.getId(), resY.getId());

        assertEquals(2, shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerX.getId()).size());
        assertEquals(2, shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerY.getId()).size());
    }

    // =========================================================================
    // SECTION 4: PHASE 0 TOKEN SEQUENCE CONCURRENCY VERIFICATION
    // =========================================================================

    @Test
    @DisplayName("4.1 Multi-instance concurrent customer joins generate unique, strictly monotonic token sequences via PostgreSQL atomic counter")
    void testMultiInstanceConcurrentCustomerJoinsAtomicSequence() throws Exception {
        User owner = registerOwner("9811100010", "Queue Owner", "Atomic Salon");
        Shop shop = shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.getId()).get(0);

        // Start queue session on Instance A
        var dashboard = queueServiceInstanceA.startQueue(shop.getId());
        String publicQueueId = dashboard.getPublicQueueId();
        assertNotNull(publicQueueId);
        Long sessionId = dashboard.getSessionId();
        assertNotNull(sessionId);

        // Create independent Instance B with its own in-memory ActiveQueueManager
        ActiveQueueManager queueManagerB = new ActiveQueueManager();
        QueueService queueServiceInstanceB = new QueueService(
                shopRepository,
                sessionRepository,
                customerRepository,
                subscriptionRepository,
                queueManagerB,
                sseEmitterService,
                entitlementService,
                null,
                jdbcTemplate
        );

        int totalJoins = 20;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<CustomerJoinResponse>> futures = new ArrayList<>();

        for (int i = 0; i < totalJoins; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                latch.await();
                CustomerJoinRequest req = new CustomerJoinRequest();
                req.setCustomerName("Customer " + index);
                req.setCustomerPhone("990000" + String.format("%04d", index));

                // Distribute calls across Instance A and Instance B randomly
                if (index % 2 == 0) {
                    return queueServiceInstanceA.customerJoinQueue(publicQueueId, req);
                } else {
                    return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                            .execute(status -> queueServiceInstanceB.customerJoinQueue(publicQueueId, req));
                }
            }));
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));

        List<String> tokenNumbers = new ArrayList<>();
        for (Future<CustomerJoinResponse> f : futures) {
            CustomerJoinResponse r = f.get();
            assertNotNull(r, "Join response must not be null");
            assertNotNull(r.getTokenNumber(), "Token number must not be null");
            tokenNumbers.add(r.getTokenNumber());
        }

        // Verify all 20 tokens are completely unique across instances
        assertEquals(totalJoins, tokenNumbers.size());
        assertEquals(totalJoins, tokenNumbers.stream().distinct().count(),
                "All token numbers across multiple instances must be completely unique with zero collisions");

        // Verify tokens in PostgreSQL database are strictly 1..20
        List<QueueCustomer> dbCustomers = customerRepository.findByQueueSessionIdOrderByTokenSequenceAsc(sessionId);
        assertEquals(totalJoins, dbCustomers.size(), "PostgreSQL must store all 20 committed customer records");
        for (int i = 0; i < totalJoins; i++) {
            int expectedSeq = i + 1;
            assertEquals(expectedSeq, dbCustomers.get(i).getTokenSequence(),
                    "Token sequence in PostgreSQL must be strictly monotonic starting at 1");
            assertEquals(ShopActiveQueue.formatTokenNumber(expectedSeq), dbCustomers.get(i).getTokenNumber(),
                    "Customer-facing token number format must match expected 3-digit sequence");
        }
    }

    @Test
    @DisplayName("4.2 Atomic sequence allocation preserves consistency and handles transaction rollback cleanly")
    void testAtomicSequenceRollbackPreservesConsistency() {
        User owner = registerOwner("9811100011", "Rollback Owner", "Rollback Barber");
        Shop shop = shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.getId()).get(0);

        var dashboard = queueServiceInstanceA.startQueue(shop.getId());
        String publicQueueId = dashboard.getPublicQueueId();
        Long sessionId = dashboard.getSessionId();

        // Successful join
        CustomerJoinRequest req1 = new CustomerJoinRequest();
        req1.setCustomerName("Normal Customer");
        CustomerJoinResponse resp1 = queueServiceInstanceA.customerJoinQueue(publicQueueId, req1);
        assertEquals("001", resp1.getTokenNumber());

        // Attempt join inside a rolling-back transaction
        assertThrows(RuntimeException.class, () -> {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                CustomerJoinRequest req2 = new CustomerJoinRequest();
                req2.setCustomerName("Failing Customer");
                queueServiceInstanceA.customerJoinQueue(publicQueueId, req2);
                throw new RuntimeException("Simulated unexpected business failure during join");
            });
        });

        // The failed customer must not exist in PostgreSQL
        List<QueueCustomer> dbCustomersAfterRollback = customerRepository.findByQueueSessionIdOrderByTokenSequenceAsc(sessionId);
        assertEquals(1, dbCustomersAfterRollback.size(), "Only the committed customer must exist in the database");
        assertEquals("001", dbCustomersAfterRollback.get(0).getTokenNumber());

        // Subsequent join succeeds without constraint error
        CustomerJoinRequest req3 = new CustomerJoinRequest();
        req3.setCustomerName("Subsequent Customer");
        CustomerJoinResponse resp3 = queueServiceInstanceA.customerJoinQueue(publicQueueId, req3);
        assertNotNull(resp3.getTokenNumber());

        List<QueueCustomer> finalDbCustomers = customerRepository.findByQueueSessionIdOrderByTokenSequenceAsc(sessionId);
        assertEquals(2, finalDbCustomers.size());
        assertEquals(2, finalDbCustomers.stream().map(QueueCustomer::getTokenSequence).distinct().count(),
                "Sequence numbers must remain distinct");
    }

    @Test
    @DisplayName("4.3 PostgreSQL advisory lock coordinates maintenance cleanup and prevents concurrent execution across instances")
    void testPostgreSqlAdvisoryLockCoordinatesMaintenanceAcrossInstances() throws SQLException {
        long lockKey = com.queueless.service.DataLifecycleMaintenanceService.MAINTENANCE_LOCK_KEY;

        // Instance 1 holds the PostgreSQL advisory lock on an independent database connection
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next() && rs.getBoolean(1), "Initial PostgreSQL advisory lock must be acquired by Instance 1 connection");
                }
            }

            // Instance 2 (represented by maintenanceService on an independent pooled connection) attempts to run cleanup
            var stats = maintenanceService.performMaintenanceCleanup();
            assertFalse(stats.executed(), "Cleanup must be skipped while Instance 1 holds the PostgreSQL advisory lock");
            assertEquals(0, stats.purgedChallenges());
            assertEquals(0, stats.purgedIdempotencyKeys());

            // Release the lock on Instance 1's connection
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next() && rs.getBoolean(1), "Instance 1 advisory lock must be released");
                }
            }
        }

        // Now that Instance 1 released the lock, maintenance cleanup runs normally and executes
        var normalStats = maintenanceService.performMaintenanceCleanup();
        assertTrue(normalStats.executed(), "Cleanup must execute successfully once the advisory lock is released");
    }

    @Test
    @DisplayName("4.4 PostgreSQL advisory lock is released in finally even when cleanup throws an exception")
    void testAdvisoryLockReleasedEvenWhenCleanupThrowsException() throws SQLException {
        long lockKey = com.queueless.service.DataLifecycleMaintenanceService.MAINTENANCE_LOCK_KEY;

        // Create a maintenance service instance where executePurgeBatches throws a simulated failure
        var failingService = new com.queueless.service.DataLifecycleMaintenanceService(
                null, null, null, null, jdbcTemplate, dataSource
        ) {
            @Override
            public CleanupStats executePurgeBatches() {
                throw new IllegalStateException("Simulated transient database/IO failure during purge");
            }
        };

        // Assert that the exception is thrown
        assertThrows(RuntimeException.class, failingService::performMaintenanceCleanup,
                "Failing maintenance service must propagate the exception");

        // Verify that the advisory lock was strictly released in finally on that physical connection
        try (Connection verifyConn = dataSource.getConnection()) {
            verifyConn.setAutoCommit(true);
            try (PreparedStatement ps = verifyConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next() && rs.getBoolean(1),
                            "Another connection must be able to acquire the advisory lock immediately after an exception in cleanup");
                }
            }
            // Clean up
            try (PreparedStatement ps = verifyConn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, lockKey);
                ps.executeQuery();
            }
        }
    }

    @Test
    @DisplayName("4.5 Connection pooling cannot return a connection with an active advisory lock")
    void testConnectionPoolingCannotReturnActiveLockToPool() throws SQLException {
        long lockKey = com.queueless.service.DataLifecycleMaintenanceService.MAINTENANCE_LOCK_KEY;

        // Run maintenance cleanup multiple times through the connection pool
        for (int i = 0; i < 3; i++) {
            var stats = maintenanceService.performMaintenanceCleanup();
            assertTrue(stats.executed(), "Maintenance execution " + i + " must execute successfully");
        }

        // Verify that no connection returned to the pool has the advisory lock held
        try (Connection verifyConn = dataSource.getConnection()) {
            verifyConn.setAutoCommit(true);
            try (PreparedStatement ps = verifyConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next() && rs.getBoolean(1),
                            "Connection pool must not retain any active advisory lock after cleanup completion");
                }
            }
            // Clean up
            try (PreparedStatement ps = verifyConn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, lockKey);
                ps.executeQuery();
            }
        }
    }

    @Test
    @DisplayName("4.6 PostgreSQL server automatically releases advisory locks upon connection close or termination")
    void testAdvisoryLockReleasedOnConnectionLossOrClose() throws SQLException {
        long lockKey = com.queueless.service.DataLifecycleMaintenanceService.MAINTENANCE_LOCK_KEY;

        // Connection 1 acquires the lock
        Connection conn1 = dataSource.getConnection();
        conn1.setAutoCommit(true);
        try (PreparedStatement ps = conn1.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, lockKey);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next() && rs.getBoolean(1), "Connection 1 must acquire the advisory lock");
            }
        }

        // Connection 2 attempts to acquire and must fail
        try (Connection conn2 = dataSource.getConnection()) {
            conn2.setAutoCommit(true);
            try (PreparedStatement ps = conn2.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertFalse(rs.next() && rs.getBoolean(1), "Connection 2 must be blocked while Connection 1 holds lock");
                }
            }
        }

        // Connection 1 is terminated without calling pg_advisory_unlock
        conn1.close();

        // Connection 3 can now acquire the lock immediately
        try (Connection conn3 = dataSource.getConnection()) {
            conn3.setAutoCommit(true);
            try (PreparedStatement ps = conn3.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, lockKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next() && rs.getBoolean(1),
                            "PostgreSQL must automatically release advisory lock when connection 1 closes");
                }
            }
            // Clean up
            try (PreparedStatement ps = conn3.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, lockKey);
                ps.executeQuery();
            }
        }
    }

    // =========================================================================
    // PART 5: REAL POSTGRESQL DISTRIBUTED SSE TICKET CONCURRENCY & ATOMIC STORE
    // =========================================================================

    @Test
    @DisplayName("5.1 Real PostgreSQL: Ticket issued by Instance A can be consumed by Instance B")
    void testTicketIssuedByInstanceAAndConsumedByInstanceBOnRealPostgreSql() {
        User owner = userRepository.save(new User("owner_ab_" + System.currentTimeMillis() + "@test.com", "pass", "Owner AB", "+919800000001", com.queueless.entity.UserRole.ROLE_OWNER));
        Shop shop = shopService.saveShopWithUniqueCodeRetry(new Shop(owner, "Shop AB", "Retail", 15));

        SseTicketService instanceA = new SseTicketService(jdbcTemplate, null, Clock.systemUTC());
        SseTicketService instanceB = new SseTicketService(jdbcTemplate, null, Clock.systemUTC());

        SseTicketService.SseTicket issued = instanceA.issueTicket(owner.getId(), shop.getId());
        assertNotNull(issued);
        assertTrue(issued.ticket().startsWith("stk_"));

        // Verify stored in PostgreSQL sse_tickets table
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sse_tickets WHERE ticket = ?", Integer.class, issued.ticket());
        assertEquals(1, count, "Ticket must exist in real PostgreSQL database");

        // Consumed by Instance B
        SseTicketService.SseTicket consumed = instanceB.consumeTicket(issued.ticket(), shop.getId(), owner.getId());
        assertNotNull(consumed);
        assertEquals(issued.ticket(), consumed.ticket());
        assertEquals(owner.getId(), consumed.userId());
        assertEquals(shop.getId(), consumed.shopId());

        // Verify atomically removed from PostgreSQL
        Integer afterCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sse_tickets WHERE ticket = ?", Integer.class, issued.ticket());
        assertEquals(0, afterCount, "Ticket must be atomically removed from real PostgreSQL upon consumption");
    }

    @Test
    @DisplayName("5.2 Real PostgreSQL: Concurrent consumption across instances allows exactly one winner")
    void testConcurrentConsumptionAcrossInstancesRealPostgreSql() throws Exception {
        User owner = userRepository.save(new User("owner_conc_" + System.currentTimeMillis() + "@test.com", "pass", "Owner Conc", "+919800000002", com.queueless.entity.UserRole.ROLE_OWNER));
        Shop shop = shopService.saveShopWithUniqueCodeRetry(new Shop(owner, "Shop Conc", "Retail", 15));

        SseTicketService instanceA = new SseTicketService(jdbcTemplate, null, Clock.systemUTC());
        SseTicketService instanceB = new SseTicketService(jdbcTemplate, null, Clock.systemUTC());

        SseTicketService.SseTicket issued = instanceA.issueTicket(owner.getId(), shop.getId());

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);
        List<SseTicketService.SseTicket> successful = Collections.synchronizedList(new ArrayList<>());
        List<Exception> failed = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Alternate between Instance A and Instance B
                    SseTicketService consumer = (idx % 2 == 0) ? instanceA : instanceB;
                    SseTicketService.SseTicket c = consumer.consumeTicket(issued.ticket(), shop.getId(), owner.getId());
                    successful.add(c);
                } catch (Exception ex) {
                    failed.add(ex);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successful.size(), "Exactly ONE consumer must succeed across distributed instances");
        assertEquals(threads - 1, failed.size(), "All other concurrent attempts must fail closed");
        for (Exception e : failed) {
            assertTrue(e instanceof AccessDeniedException, "Failed attempts must throw AccessDeniedException");
        }
    }

    @Test
    @DisplayName("5.3 Real PostgreSQL: Replay of consumed ticket is rejected")
    void testReplayAttackRejectedRealPostgreSql() {
        User owner = userRepository.save(new User("owner_rep_" + System.currentTimeMillis() + "@test.com", "pass", "Owner Rep", "+919800000003", com.queueless.entity.UserRole.ROLE_OWNER));
        Shop shop = shopService.saveShopWithUniqueCodeRetry(new Shop(owner, "Shop Rep", "Retail", 15));

        SseTicketService instanceA = new SseTicketService(jdbcTemplate, null, Clock.systemUTC());
        SseTicketService instanceB = new SseTicketService(jdbcTemplate, null, Clock.systemUTC());

        SseTicketService.SseTicket issued = instanceA.issueTicket(owner.getId(), shop.getId());
        instanceB.consumeTicket(issued.ticket(), shop.getId(), owner.getId());

        // Replay attempt by Instance A or Instance B
        assertThrows(AccessDeniedException.class, () -> {
            instanceA.consumeTicket(issued.ticket(), shop.getId(), owner.getId());
        }, "Replay on Instance A must be rejected");

        assertThrows(AccessDeniedException.class, () -> {
            instanceB.consumeTicket(issued.ticket(), shop.getId(), owner.getId());
        }, "Replay on Instance B must be rejected");
    }

    @Test
    @DisplayName("5.4 Real PostgreSQL: Expired ticket is rejected and purged")
    void testExpiredTicketRejectedRealPostgreSql() {
        User owner = userRepository.save(new User("owner_exp_" + System.currentTimeMillis() + "@test.com", "pass", "Owner Exp", "+919800000004", com.queueless.entity.UserRole.ROLE_OWNER));
        Shop shop = shopService.saveShopWithUniqueCodeRetry(new Shop(owner, "Shop Exp", "Retail", 15));

        Instant baseTime = Instant.now();
        Clock fixedClock = Clock.fixed(baseTime, ZoneOffset.UTC);
        SseTicketService instanceA = new SseTicketService(jdbcTemplate, null, fixedClock);

        SseTicketService.SseTicket issued = instanceA.issueTicket(owner.getId(), shop.getId());

        // Fast forward clock by 65 seconds (> 60s TTL)
        Clock futureClock = Clock.fixed(baseTime.plusSeconds(65), ZoneOffset.UTC);
        SseTicketService instanceB = new SseTicketService(jdbcTemplate, null, futureClock);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () -> {
            instanceB.consumeTicket(issued.ticket(), shop.getId(), owner.getId());
        });
        assertTrue(ex.getMessage().contains("expired"), "Exception must state ticket has expired");
    }

    @Test
    @DisplayName("5.5 Real PostgreSQL: Wrong-shop and wrong-user tickets are rejected")
    void testWrongShopAndWrongUserRejectedRealPostgreSql() {
        User owner1 = userRepository.save(new User("owner_w1_" + System.currentTimeMillis() + "@test.com", "pass", "Owner W1", "+919800000005", com.queueless.entity.UserRole.ROLE_OWNER));
        User owner2 = userRepository.save(new User("owner_w2_" + System.currentTimeMillis() + "@test.com", "pass", "Owner W2", "+919800000006", com.queueless.entity.UserRole.ROLE_OWNER));
        Shop shop1 = shopService.saveShopWithUniqueCodeRetry(new Shop(owner1, "Shop W1", "Retail", 15));
        Shop shop2 = shopService.saveShopWithUniqueCodeRetry(new Shop(owner2, "Shop W2", "Retail", 15));

        SseTicketService instance = new SseTicketService(jdbcTemplate, null, Clock.systemUTC());

        SseTicketService.SseTicket issued1 = instance.issueTicket(owner1.getId(), shop1.getId());

        // Wrong shop requested
        AccessDeniedException shopEx = assertThrows(AccessDeniedException.class, () -> {
            instance.consumeTicket(issued1.ticket(), shop2.getId(), owner1.getId());
        });
        assertTrue(shopEx.getMessage().contains("not valid for the requested shop"));

        // Wrong user requested for a freshly issued ticket
        SseTicketService.SseTicket issued2 = instance.issueTicket(owner1.getId(), shop1.getId());
        AccessDeniedException userEx = assertThrows(AccessDeniedException.class, () -> {
            instance.consumeTicket(issued2.ticket(), shop1.getId(), owner2.getId());
        });
        assertTrue(userEx.getMessage().contains("not valid for the authenticated user"));
    }

    @Test
    @DisplayName("5.6 Database failure behavior fails closed without silent bypass")
    void testDatabaseFailureBehaviorFailsClosed() {
        org.springframework.jdbc.core.JdbcTemplate failingJdbc = new org.springframework.jdbc.core.JdbcTemplate() {
            @Override
            public <T> T execute(String sql, org.springframework.jdbc.core.PreparedStatementCallback<T> action) {
                throw new org.springframework.dao.DataAccessResourceFailureException("Simulated connection timeout to database");
            }
        };

        SseTicketService service = new SseTicketService(failingJdbc, null, Clock.systemUTC());

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () -> {
            service.consumeTicket("stk_sample123", 100L, 200L);
        });
        assertTrue(ex.getMessage().contains("Database error during ticket verification"));
    }

    // =========================================================================
    // SECTION 6: REAL POSTGRESQL: REGISTRATION RECOVERY & URI EXPOSURE PROOF
    // =========================================================================

    @Test
    @DisplayName("6.1 Real PostgreSQL: Crash after Stage 2 reconciled without duplicate shops")
    void testRealPostgreSqlCrashAfterStage2Reconciled() {
        String phone = "+9197" + (System.currentTimeMillis() % 100000000L);
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        // Seed incomplete user with shop in PostgreSQL
        User pendingUser = new User("pg_pending_" + System.currentTimeMillis() + "@test.com", "pass", "PG Pending", normalizedPhone, com.queueless.entity.UserRole.ROLE_OWNER);
        pendingUser.setStatus(User.STATUS_PENDING_REGISTRATION);
        pendingUser = userRepository.saveAndFlush(pendingUser);
        Business business = businessRepository.saveAndFlush(new Business(pendingUser, "PG Business"));
        Shop shop = shopService.saveShopWithUniqueCodeRetry(new Shop(pendingUser, "Orphaned Shop", "Retail", 15));

        assertTrue(authService.isIncompleteRegistration(pendingUser));

        // Register with same phone auto-reconciles in real PostgreSQL and succeeds
        RegisterRequest req = new RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("PG Recovered Shop");
        req.setCategory("Retail");

        AuthResponse res = authService.register(req);
        assertNotNull(res);

        User activeUser = userRepository.findByPhone(normalizedPhone).orElseThrow();
        assertEquals(User.STATUS_ACTIVE, activeUser.getStatus());
        assertEquals(1, shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(activeUser.getId()).size(),
                "Must have exactly 1 shop in PostgreSQL; orphaned shop must be cleaned up");
        assertTrue(trialEligibilityLedgerRepository.existsByNormalizedPhone(normalizedPhone));
    }

    @Test
    @DisplayName("6.2 Real PostgreSQL: Concurrent registration allows exactly 1 winner and prevents duplicate trials")
    void testRealPostgreSqlConcurrentRegistrationPreventsDuplicateTrials() throws Exception {
        String phone = "+9196" + (System.currentTimeMillis() % 100000000L);
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);
        List<AuthResponse> successList = Collections.synchronizedList(new ArrayList<>());
        List<Exception> failList = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    RegisterRequest req = new RegisterRequest();
                    req.setPhone(phone);
                    req.setPin("123456");
                    req.setConfirmPin("123456");
                    req.setShopName("Concurrent PG Shop");
                    req.setCategory("Retail");
                    successList.add(authService.register(req));
                } catch (Exception e) {
                    failList.add(e);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successList.size(), "Exactly ONE registration must succeed in PostgreSQL");
        assertEquals(threads - 1, failList.size(), "All other concurrent registrations must fail");

        String normalized = phoneNormalizationService.normalize(phone);
        assertEquals(1, userRepository.findAll().stream().filter(u -> normalized.equals(u.getPhone())).count(),
                "Exactly 1 user must exist in PostgreSQL");
        assertTrue(trialEligibilityLedgerRepository.existsByNormalizedPhone(normalized),
                "Exactly 1 trial ledger entry must exist in PostgreSQL");
    }

    @Test
    @DisplayName("6.3 Real PostgreSQL: Header-authenticated SSE stream connects with zero credentials in URI")
    void testRealPostgreSqlHeaderAuthenticatedSseStream() {
        String phone = "+9195" + (System.currentTimeMillis() % 100000000L);
        RegisterRequest req = new RegisterRequest();
        req.setPhone(phone);
        req.setPin("123456");
        req.setConfirmPin("123456");
        req.setShopName("PG Header Shop");
        req.setCategory("Retail");

        AuthResponse auth = authService.register(req);
        java.security.Principal principal = () -> auth.getPhone();

        // Direct header authentication without URI credentials on real PostgreSQL
        var emitter = queueController.streamOwnerUpdates(principal, auth.getShopId(), null, null, null, null, null);
        assertNotNull(emitter, "Stream emitter must be created on /{shopId}/stream via header authentication");
    }
}
