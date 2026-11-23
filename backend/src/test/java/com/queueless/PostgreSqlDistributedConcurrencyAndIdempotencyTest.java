package com.queueless;

import com.queueless.dto.BranchSummaryResponse;
import com.queueless.dto.CreateBranchRequest;
import com.queueless.dto.CustomerJoinRequest;
import com.queueless.dto.CustomerJoinResponse;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.AuditLog;
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
import com.queueless.service.SseEmitterService;
import com.queueless.service.SubscriptionService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
                idempotencyKeyRepository
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
}
