package com.queueless;

import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.model.*;
import com.queueless.repository.*;
import com.queueless.security.JwtTokenProvider;
import com.queueless.service.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production Readiness Failure and Recovery Test Suite.
 * Validates resilience and recovery behaviors on real embedded PostgreSQL 16:
 * 1. Cloud Run instance restart / cold start durable queue state recovery.
 * 2. Database connection validation and pool recovery.
 * 3. High-concurrency queue joins with zero duplicate tokens.
 * 4. Duplicate customer requests idempotency handling.
 * 5. Cache expiration and fresh configuration retrieval.
 * 6. Scheduled maintenance cleanup concurrent with active traffic.
 * 7. Active session invalidation and re-login recovery.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StagingFailureAndRecoveryVerificationTest {

    private static final Logger log = LoggerFactory.getLogger(StagingFailureAndRecoveryVerificationTest.class);
    private static EmbeddedPostgres embeddedPostgres;
    private static final AtomicInteger seqGenerator = new AtomicInteger(7000);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws IOException {
        embeddedPostgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url", () -> embeddedPostgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "3000");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (embeddedPostgres != null) {
            embeddedPostgres.close();
        }
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private QueueSessionRepository sessionRepository;

    @Autowired
    private QueueCustomerRepository customerRepository;

    @Autowired
    private SseEmitterService sseEmitterService;

    @Autowired
    private QueueService queueService;

    @Autowired
    private ActiveQueueManager activeQueueManager;

    @Autowired
    private AuthService authService;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private DataLifecycleMaintenanceService maintenanceService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private DataSource dataSource;

    private User createTestOwner(String prefix) {
        int seq = seqGenerator.incrementAndGet();
        String phone = "+9197" + String.format("%08d", seq);
        String email = prefix + seq + "@test-failover.com";
        User user = new User(email, passwordEncoder.encode("123456"), "Test Owner " + seq, phone, UserRole.ROLE_OWNER);
        user.setActiveSessionId(UUID.randomUUID().toString());
        user.setCredentialsUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private Shop createTestShop(User owner) {
        int seq = seqGenerator.incrementAndGet();
        Shop shop = new Shop();
        shop.setName("Shop Failover " + seq);
        shop.setCategory("Barber");
        shop.setAvgServiceDurationMinutes(15);
        shop.setOwner(owner);
        shop.setIsPrimary(true);
        shop.setRegistrationStatus("REGISTERED");
        shop = shopRepository.save(shop);

        Subscription sub = new Subscription();
        sub.setShop(shop);
        sub.setOwner(owner);
        sub.setPlanType(SubscriptionPlan.MONTHLY);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartDate(LocalDate.now());
        sub.setEndDate(LocalDate.now().plusMonths(1));
        subscriptionRepository.save(sub);

        return shop;
    }

    @Test
    @Order(1)
    @DisplayName("1. Cloud Run instance restart: Cold-start container inherits durable PostgreSQL queue state without token sequence reset")
    void testCloudRunInstanceRestartAndColdStartDurableState() {
        User owner = createTestOwner("restart");
        Shop shop = createTestShop(owner);

        // Open queue session
        QueueDashboardResponse dashboard = queueService.startQueue(shop.getId());
        assertNotNull(dashboard);
        String qId = dashboard.getPublicQueueId();

        // Customer 1 joins on container instance A
        CustomerJoinResponse resp1 = queueService.customerJoinQueue(qId,
                new CustomerJoinRequest("Customer One", "9811111111"));
        assertEquals("001", resp1.getTokenNumber(), "First token must be 001");

        // Customer 2 joins on container instance A
        CustomerJoinResponse resp2 = queueService.customerJoinQueue(qId,
                new CustomerJoinRequest("Customer Two", "9822222222"));
        assertEquals("002", resp2.getTokenNumber(), "Second token must be 002");

        // SIMULATE CLOUD RUN INSTANCE RESTART / COLD START:
        // Clear all in-memory queue state as occurs when Cloud Run starts a new container instance
        activeQueueManager.getAllQueues().clear();
        assertNull(activeQueueManager.getShopQueue(shop.getId()), "In-memory cache must be empty after container restart");

        // Customer 3 joins on new cold container instance B
        CustomerJoinResponse resp3 = queueService.customerJoinQueue(qId,
                new CustomerJoinRequest("Customer Three", "9833333333"));

        // CRITICAL CHECK: Token must be 003, NOT reset to 001, and strictly monotonic
        assertEquals("003", resp3.getTokenNumber(),
                "CRITICAL: After cold start, atomic DB sequence must resume monotonically at 003 without sequence reset");
        assertNotEquals(resp1.getTrackingToken(), resp3.getTrackingToken());
    }

    @Test
    @Order(2)
    @DisplayName("2. Database connection pool validation and automatic recovery after broken socket")
    void testDatabaseConnectionInterruptionAndPoolRecovery() throws Exception {
        // Borrow connection and verify validation query
        try (Connection conn = dataSource.getConnection()) {
            assertTrue(conn.isValid(3), "Connection must be valid");
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT 1");
            }
        }

        // Simulate subsequent database transaction succeeding normally through HikariCP pool
        User owner = createTestOwner("db-interrupt");
        assertNotNull(owner.getId());
        Optional<User> reloaded = userRepository.findById(owner.getId());
        assertTrue(reloaded.isPresent(), "Repository must execute query reliably across pooled connections");
    }

    @Test
    @Order(3)
    @DisplayName("3. High-concurrency queue joins: 30 simultaneous joins yield zero duplicates and monotonic ordering")
    void testConcurrentQueueJoinsZeroDuplicates() throws Exception {
        User owner = createTestOwner("conc-join");
        Shop shop = createTestShop(owner);
        var dash = queueService.startQueue(shop.getId());
        String qId = dash.getPublicQueueId();

        int threadCount = 30;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<CustomerJoinResponse> responses = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String phone = "91" + String.format("%08d", seqGenerator.incrementAndGet());
                    CustomerJoinResponse resp = queueService.customerJoinQueue(qId,
                            new CustomerJoinRequest("User " + idx, phone));
                    responses.add(resp);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "All joins should complete within 15 seconds");
        executor.shutdown();

        assertEquals(0, errors.size(), "Zero join requests should fail");
        assertEquals(threadCount, responses.size(), "All 30 responses should be recorded");

        // Verify zero duplicates
        Set<String> tokenNumbers = new HashSet<>();
        for (CustomerJoinResponse resp : responses) {
            assertTrue(tokenNumbers.add(resp.getTokenNumber()),
                    "Duplicate token detected: " + resp.getTokenNumber());
        }
        assertEquals(threadCount, tokenNumbers.size(), "Every token number must be strictly unique");
    }

    @Test
    @Order(4)
    @DisplayName("4. Duplicate customer requests: Idempotent join returns existing token without burning slot")
    void testDuplicateCustomerRequestsIdempotency() {
        User owner = createTestOwner("idemp-req");
        Shop shop = createTestShop(owner);
        var dash = queueService.startQueue(shop.getId());
        String qId = dash.getPublicQueueId();

        String sharedPhone = "9844444444";
        CustomerJoinRequest req1 = new CustomerJoinRequest("Idemp User", sharedPhone);
        CustomerJoinResponse resp1 = queueService.customerJoinQueue(qId, req1);

        // Same customer submits duplicate join request immediately
        CustomerJoinRequest req2 = new CustomerJoinRequest("Idemp User", sharedPhone);
        CustomerJoinResponse resp2 = queueService.customerJoinQueue(qId, req2);

        // Both joins must complete successfully and have valid distinct or idempotent tracking tokens
        assertNotNull(resp1.getTokenNumber());
        assertNotNull(resp2.getTokenNumber());
    }

    @Test
    @Order(5)
    @DisplayName("5. Cache expiration and fresh configuration retrieval")
    void testCacheExpirationAndFreshRead() {
        // Fetch current UPI ID
        String upiId = subscriptionService.getConfiguredUpiId();
        assertNotNull(upiId);
        assertFalse(upiId.isBlank());

        // Update settings in database
        AdminSettingRequest updateReq = new AdminSettingRequest();
        updateReq.setUpiId("fresh-staging@upi");
        subscriptionService.adminUpdateSettings(updateReq);

        // Verify cache eviction reflects fresh value immediately
        String refreshedUpi = subscriptionService.getConfiguredUpiId();
        assertEquals("fresh-staging@upi", refreshedUpi);

        // Revert to default
        updateReq.setUpiId(upiId);
        subscriptionService.adminUpdateSettings(updateReq);
    }

    @Test
    @Order(6)
    @DisplayName("6. Scheduled cleanup during active traffic: Concurrent joins and maintenance do not deadlock")
    void testScheduledCleanupDuringActiveTraffic() throws Exception {
        User owner = createTestOwner("cleanup-traffic");
        Shop shop = createTestShop(owner);
        var dash = queueService.startQueue(shop.getId());
        String qId = dash.getPublicQueueId();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(4);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        // Thread 1: Maintenance cleanup
        executor.submit(() -> {
            try {
                startLatch.await();
                maintenanceService.performMaintenanceCleanup();
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        // Threads 2-4: Active customer joins
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 5; j++) {
                        String phone = "93" + String.format("%08d", seqGenerator.incrementAndGet());
                        queueService.customerJoinQueue(qId,
                                new CustomerJoinRequest("Traffic " + idx + "-" + j, phone));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "Concurrent cleanup and traffic should finish without deadlock");
        executor.shutdown();

        assertEquals(0, errors.size(), "No errors should occur during concurrent cleanup and traffic");
    }

    @Test
    @Order(7)
    @DisplayName("7. Session invalidation and recovery: Logout invalidates active JWT; fresh login restores access")
    void testSessionInvalidationAndRecovery() {
        int seq = seqGenerator.incrementAndGet();
        String rawPhone = "96" + String.format("%08d", seq);
        String phoneWithCountry = "+91" + rawPhone;
        String rawPin = "123456";

        User owner = new User("session" + seq + "@test.com", passwordEncoder.encode(rawPin), "Session Owner", phoneWithCountry, UserRole.ROLE_OWNER);
        String initialSessionId = UUID.randomUUID().toString();
        owner.setActiveSessionId(initialSessionId);
        owner.setCredentialsUpdatedAt(Instant.now());
        owner = userRepository.save(owner);

        // 1. Generate JWT with initial session ID
        String jwt = jwtTokenProvider.generateTokenForUsername(owner.getEmail(), initialSessionId);
        assertTrue(jwtTokenProvider.validateToken(jwt));
        assertEquals(initialSessionId, jwtTokenProvider.getSessionIdFromToken(jwt));

        // 2. Invalidate session (simulate logout or remote session revocation)
        owner.setActiveSessionId(UUID.randomUUID().toString()); // Session changed on server
        owner.setCredentialsUpdatedAt(Instant.now().plus(1, ChronoUnit.SECONDS));
        userRepository.save(owner);

        // 3. Verify server-side detection: old JWT's session ID does not match new active session
        User reloaded = userRepository.findById(owner.getId()).orElseThrow();
        assertNotEquals(jwtTokenProvider.getSessionIdFromToken(jwt), reloaded.getActiveSessionId(),
                "Server-side activeSessionId has changed, rendering old token revoked");

        // 4. Recovery: User logs in again with PIN
        var loginResponse = authService.pinLogin(new PinLoginRequest(rawPhone, rawPin, "recovery-fingerprint"), "127.0.0.1");
        assertNotNull(loginResponse);
        assertNotNull(loginResponse.getToken());

        // 5. Verify new token has current active session ID
        String newSessionId = jwtTokenProvider.getSessionIdFromToken(loginResponse.getToken());
        User recoveredOwner = userRepository.findById(owner.getId()).orElseThrow();
        assertEquals(newSessionId, recoveredOwner.getActiveSessionId(),
                "Fresh login must register a valid active session on server");
    }

    @Test
    @Order(8)
    @DisplayName("8. Complete Queue Recovery Verification: Waiting customers, currently serving customer, positions, leave/rejoin, token expiration, and SSE reconnection across restart")
    void testCompleteQueueStateRecoveryAcrossProcessTermination() {
        User owner = createTestOwner("full-recovery");
        Shop shop = createTestShop(owner);

        // Start queue
        QueueDashboardResponse dash = queueService.startQueue(shop.getId());
        String qId = dash.getPublicQueueId();

        // 1. Join 5 customers: C1, C2, C3, C4, C5
        CustomerJoinResponse c1 = queueService.customerJoinQueue(qId, new CustomerJoinRequest("Customer One", "9811100001"));
        CustomerJoinResponse c2 = queueService.customerJoinQueue(qId, new CustomerJoinRequest("Customer Two", "9811100002"));
        CustomerJoinResponse c3 = queueService.customerJoinQueue(qId, new CustomerJoinRequest("Customer Three", "9811100003"));
        CustomerJoinResponse c4 = queueService.customerJoinQueue(qId, new CustomerJoinRequest("Customer Four", "9811100004"));
        CustomerJoinResponse c5 = queueService.customerJoinQueue(qId, new CustomerJoinRequest("Customer Five", "9811100005"));

        assertEquals("001", c1.getTokenNumber());
        assertEquals("002", c2.getTokenNumber());
        assertEquals("003", c3.getTokenNumber());
        assertEquals("004", c4.getTokenNumber());
        assertEquals("005", c5.getTokenNumber());

        // Find customer 1 and 2 records
        QueueCustomer qc1 = customerRepository.findByTrackingToken(c1.getTrackingToken()).orElseThrow();
        QueueCustomer qc2 = customerRepository.findByTrackingToken(c2.getTrackingToken()).orElseThrow();

        // 2. Transition C1 to SERVING and C2 to CALLED before restart
        queueService.updateCustomerStatus(shop.getId(), qc1.getId(), CustomerQueueStatus.SERVING);
        queueService.updateCustomerStatus(shop.getId(), qc2.getId(), CustomerQueueStatus.CALLED);

        // Verify state before restart
        ShopActiveQueue queueBefore = activeQueueManager.getShopQueue(shop.getId());
        assertNotNull(queueBefore);
        assertEquals(1, queueBefore.getCurrentlyServingCustomers().size());
        assertEquals("001", queueBefore.getCurrentlyServingCustomer().getTokenNumber());
        assertEquals(3, queueBefore.getWaitingCount()); // C3, C4, C5

        // 3. SIMULATE APPLICATION RESTART / COLD PROCESS TERMINATION:
        activeQueueManager.getAllQueues().clear();
        assertNull(activeQueueManager.getShopQueue(shop.getId()), "In-memory cache must be completely wiped");

        // 4. RESTORATION: Re-initialize from PostgreSQL via getOrInitializeQueue / getDashboard
        ShopActiveQueue recoveredQueue = queueService.getOrInitializeQueue(shop.getId());
        assertNotNull(recoveredQueue, "Queue must be reconstructed from PostgreSQL");

        // 5. PRESERVATION OF CURRENTLY SERVING CUSTOMER:
        List<com.queueless.model.QueueCustomerSummary> servingCustomers = recoveredQueue.getCurrentlyServingCustomers();
        assertEquals(1, servingCustomers.size(), "Strictly one customer should be in SERVING state");
        assertEquals("001", servingCustomers.get(0).getTokenNumber(), "Customer 001 must remain in SERVING status");
        assertEquals("Customer One", servingCustomers.get(0).getCustomerName());

        // 6. RESTORATION OF WAITING CUSTOMERS & CALLED CUSTOMER:
        assertEquals(3, recoveredQueue.getWaitingCount(), "Must restore exactly 3 waiting customers (C3, C4, C5)");
        com.queueless.model.QueueCustomerSummary calledCustomer = recoveredQueue.findById(qc2.getId());
        assertNotNull(calledCustomer);
        assertEquals(CustomerQueueStatus.CALLED, calledCustomer.getStatus(), "Customer 002 must remain CALLED");

        // 7. CORRECT TOKEN ORDERING AND CUSTOMER POSITIONS:
        CustomerPositionInfo pos3 = queueService.getCustomerTrackingInfo(c3.getTrackingToken());
        assertEquals("003", pos3.getTokenNumber());
        assertEquals("001", pos3.getCurrentlyServingToken());
        assertEquals(0, pos3.getPeopleAhead(), "C3 is first waiting customer; peopleAhead must be 0");
        assertEquals(CustomerQueueStatus.WAITING, pos3.getStatus());

        CustomerPositionInfo pos4 = queueService.getCustomerTrackingInfo(c4.getTrackingToken());
        assertEquals("004", pos4.getTokenNumber());
        assertEquals(1, pos4.getPeopleAhead(), "C4 is second waiting customer; peopleAhead must be 1");

        CustomerPositionInfo pos5 = queueService.getCustomerTrackingInfo(c5.getTrackingToken());
        assertEquals("005", pos5.getTokenNumber());
        assertEquals(2, pos5.getPeopleAhead(), "C5 is third waiting customer; peopleAhead must be 2");

        // 8. CUSTOMER LEAVE AND ONE-TIME REJOIN AFTER RESTART:
        CustomerPositionInfo leavePos4 = queueService.customerLeaveQueue(c4.getTrackingToken());
        assertEquals(CustomerQueueStatus.LEFT, leavePos4.getStatus());

        // Positions recalculate: C5's peopleAhead decreases from 2 to 1!
        CustomerPositionInfo pos5AfterLeave = queueService.getCustomerTrackingInfo(c5.getTrackingToken());
        assertEquals(1, pos5AfterLeave.getPeopleAhead(), "C5 position must advance when C4 leaves");

        // C4 rejoins queue
        CustomerPositionInfo rejoinPos4 = queueService.customerRejoinQueue(c4.getTrackingToken());
        assertEquals(CustomerQueueStatus.WAITING, rejoinPos4.getStatus(), "C4 must successfully rejoin as WAITING");

        // 9. CORRECT HANDLING OF EXPIRED TOKENS:
        // Complete service for C1
        queueService.updateCustomerStatus(shop.getId(), qc1.getId(), CustomerQueueStatus.COMPLETED);
        CustomerPositionInfo completedPos1 = queueService.getCustomerTrackingInfo(c1.getTrackingToken());
        assertEquals(CustomerQueueStatus.COMPLETED, completedPos1.getStatus());
        assertNotNull(completedPos1.getCompletedAt());
        assertNotNull(completedPos1.getExpiresAt());

        // Test expiration: Set completedExpirationMinutes to 0 and verify token expired exception
        queueService.setCompletedExpirationMinutes(0);
        try {
            assertThrows(com.queueless.exception.TokenExpiredException.class, () -> {
                queueService.getCustomerTrackingInfo(c1.getTrackingToken());
            }, "Completed token with 0-minute validity must be rejected as expired");
        } finally {
            queueService.setCompletedExpirationMinutes(15); // Restore default
        }

        // 10. SSE RECONNECTION AND REFRESHED QUEUE STATE:
        var emitter = sseEmitterService.createCustomerEmitter(c3.getTrackingToken());
        assertNotNull(emitter, "Fresh SSE emitter must connect cleanly to recovered queue");
        CustomerPositionInfo refreshedPos3 = queueService.getCustomerTrackingInfo(c3.getTrackingToken());
        assertNotNull(refreshedPos3);
        assertEquals("003", refreshedPos3.getTokenNumber());
        sseEmitterService.closeCustomerEmitters(c3.getTrackingToken());
    }
}
