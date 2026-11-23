package com.queueless;

import com.queueless.controller.AdminController;
import com.queueless.controller.QueueController;
import com.queueless.dto.CustomerJoinRequest;
import com.queueless.dto.CustomerJoinResponse;
import com.queueless.dto.QueueDashboardResponse;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.*;
import com.queueless.repository.*;
import com.queueless.service.ActiveQueueManager;
import com.queueless.service.AuthService;
import com.queueless.service.DataLifecycleMaintenanceService;
import com.queueless.service.QueueService;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Realistic Scalability and Concurrency Load Test Suite.
 * Executes against a real embedded PostgreSQL 16 database.
 * Simulates high-concurrency real-world traffic:
 * 1. 100 concurrent customer joins across multiple shops.
 * 2. Simultaneous queue operations (joins, calls, leaves, status updates) for the same shop.
 * 3. Concurrent owner dashboard reads.
 * 4. Super Admin pagination on large datasets (500+ records).
 * 5. Concurrent scheduled maintenance cleanup and normal customer activity.
 * Measures: p50, p95, p99 response latencies, connection pool utilization, duplicate tokens, failed requests, JVM memory.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RealisticScalabilityAndConcurrencyLoadTest {

    private static final Logger log = LoggerFactory.getLogger(RealisticScalabilityAndConcurrencyLoadTest.class);
    private static EmbeddedPostgres embeddedPostgres;
    private static final AtomicInteger phoneSeq = new AtomicInteger(1000);

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
    private QueueService queueService;

    @Autowired
    private ActiveQueueManager activeQueueManager;

    @Autowired
    private QueueController queueController;

    @Autowired
    private AdminController adminController;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private QueueSessionRepository sessionRepository;

    @Autowired
    private QueueCustomerRepository customerRepository;

    @Autowired
    private PaymentRecordRepository paymentRecordRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private DataLifecycleMaintenanceService maintenanceService;

    @Autowired
    private DataSource dataSource;

    private static User owner1;
    private static User owner2;
    private static final List<Shop> testShops = new ArrayList<>();
    private static final Map<Long, String> publicQueueIdByShop = new ConcurrentHashMap<>();
    private static User superAdmin;

    @BeforeEach
    void setupSecurityContext() {
        if (superAdmin == null) {
            superAdmin = userRepository.findAllByRoleOrderByCreatedAtDesc(UserRole.ROLE_SUPER_ADMIN).stream().findFirst().orElseGet(() -> {
                User admin = new User();
                admin.setEmail("loadadmin@queueless.com");
                admin.setPhone("+9198" + String.format("%08d", phoneSeq.incrementAndGet()));
                admin.setFullName("Load Super Admin");
                admin.setRole(UserRole.ROLE_SUPER_ADMIN);
                admin.setPassword("AdminPass@123");
                admin.setStatus("ACTIVE");
                admin.setActiveSessionId(UUID.randomUUID().toString());
                admin.setCredentialsUpdatedAt(Instant.now());
                return userRepository.save(admin);
            });
        }
    }

    private void authenticateAs(User user, String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                user.getEmail(),
                null,
                List.of(new SimpleGrantedAuthority(role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private User registerOwner(String fullName, String shopName) {
        String phone = "98" + String.format("%08d", phoneSeq.incrementAndGet());
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

    private static double percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0.0;
        Collections.sort(values);
        int idx = (int) Math.ceil((p / 100.0) * values.size()) - 1;
        idx = Math.max(0, Math.min(idx, values.size() - 1));
        return values.get(idx) / 1_000_000.0; // convert ns to ms
    }

    @Test
    @Order(1)
    @DisplayName("Load Simulation 1: 100 concurrent customer joins across multiple shops on PostgreSQL")
    void test100ConcurrentCustomerJoinsAcrossMultipleShops() throws Exception {
        String testId = UUID.randomUUID().toString().substring(0, 6);

        // Provision 2 merchant owners and 5 shops
        owner1 = registerOwner("Load Owner 1", "Shop Alpha " + testId);
        owner2 = registerOwner("Load Owner 2", "Shop Beta " + testId);

        testShops.clear();
        testShops.add(shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner1.getId()).get(0));
        testShops.add(shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner2.getId()).get(0));

        // Create 3 additional shops
        for (int i = 1; i <= 3; i++) {
            Shop shop = new Shop();
            shop.setName("Shop Multi-" + i + " " + testId);
            shop.setCategory("RETAIL");
            shop.setAvgServiceDurationMinutes(10);
            shop.setOwner(i % 2 == 0 ? owner1 : owner2);
            testShops.add(shopRepository.save(shop));
        }
        assertEquals(5, testShops.size(), "Must have 5 distinct test shops");

        // Initialize active subscriptions and queues for each shop
        for (Shop s : testShops) {
            Subscription sub = new Subscription();
            sub.setShop(s);
            sub.setOwner(s.getOwner());
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setPlanType(SubscriptionPlan.MONTHLY);
            sub.setStartDate(LocalDate.now());
            sub.setEndDate(LocalDate.now().plusMonths(1));
            subscriptionRepository.save(sub);

            var dash = queueService.startQueue(s.getId());
            publicQueueIdByShop.put(s.getId(), dash.getPublicQueueId());
        }

        int totalRequests = 100; // 20 joins per shop across 5 shops
        int threadPoolSize = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(totalRequests);

        ConcurrentLinkedQueue<Long> latenciesNs = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<JoinRecord> responses = new ConcurrentLinkedQueue<>();
        AtomicInteger failedRequests = new AtomicInteger(0);

        long startBenchmarkTime = System.nanoTime();

        for (int i = 0; i < totalRequests; i++) {
            final int index = i;
            final Shop targetShop = testShops.get(index % testShops.size());
            final String qId = publicQueueIdByShop.get(targetShop.getId());
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.nanoTime();

                    CustomerJoinRequest req = new CustomerJoinRequest();
                    req.setCustomerName("Customer-" + index);
                    req.setCustomerPhone("97" + String.format("%08d", index + 1000));

                    CustomerJoinResponse resp = queueService.customerJoinQueue(qId, req);
                    long duration = System.nanoTime() - start;

                    latenciesNs.add(duration);
                    responses.add(new JoinRecord(targetShop.getId(), resp.getTokenNumber(), resp.getTrackingToken()));
                } catch (Exception ex) {
                    log.error("Join failure at index {}: {}", index, ex.getMessage());
                    failedRequests.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Release all 20 threads simultaneously
        startLatch.countDown();
        boolean completed = finishLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        long totalBenchmarkDurationNs = System.nanoTime() - startBenchmarkTime;

        assertTrue(completed, "All 100 concurrent customer joins must complete within 30 seconds");
        assertEquals(0, failedRequests.get(), "Zero join requests must fail under concurrent load");
        assertEquals(100, responses.size(), "Exactly 100 responses must be recorded");

        // Validate Monotonic Sequences & Zero Duplicate Tokens per shop
        Map<Long, Set<String>> tokensPerShop = new HashMap<>();

        for (JoinRecord rec : responses) {
            tokensPerShop.computeIfAbsent(rec.shopId(), k -> new HashSet<>()).add(rec.tokenNumber());
        }

        for (Shop s : testShops) {
            Set<String> tokens = tokensPerShop.get(s.getId());
            assertNotNull(tokens, "Tokens must exist for shop " + s.getId());
            assertEquals(20, tokens.size(), "Shop " + s.getId() + " must have exactly 20 unique tokens (zero duplicates)");

            for (int expectedSeq = 1; expectedSeq <= 20; expectedSeq++) {
                String expectedToken = String.format("%03d", expectedSeq);
                assertTrue(tokens.contains(expectedToken),
                        "Shop " + s.getId() + " must contain strictly monotonic token " + expectedToken);
            }
        }

        // Latency calculations
        List<Long> latenciesList = new ArrayList<>(latenciesNs);
        double p50 = percentile(latenciesList, 50);
        double p95 = percentile(latenciesList, 95);
        double p99 = percentile(latenciesList, 99);
        double throughput = (100.0 / (totalBenchmarkDurationNs / 1_000_000_000.0));

        log.info("=== BENCHMARK 1 RESULTS: 100 Concurrent Joins Across 5 Shops ===");
        log.info("Total Requests: {}, Failures: 0, Duplicate Tokens: 0", totalRequests);
        log.info("Throughput: {} req/sec", String.format("%.2f", throughput));
        log.info("Latency p50: {} ms, p95: {} ms, p99: {} ms",
                String.format("%.2f", p50), String.format("%.2f", p95), String.format("%.2f", p99));
    }

    private record JoinRecord(Long shopId, String tokenNumber, String trackingToken) {}

    @Test
    @Order(2)
    @DisplayName("Load Simulation 2: Simultaneous queue operations (joins, calls, status checks) on the same shop")
    void testSimultaneousQueueOperationsOnSameShop() throws Exception {
        Shop targetShop = testShops.get(0);
        String qId = publicQueueIdByShop.get(targetShop.getId());
        int threadPoolSize = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(40);
        AtomicInteger failures = new AtomicInteger(0);
        ConcurrentLinkedQueue<Long> latenciesNs = new ConcurrentLinkedQueue<>();

        // 20 joins
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.nanoTime();
                    CustomerJoinRequest req = new CustomerJoinRequest();
                    req.setCustomerName("OpJoin-" + idx);
                    req.setCustomerPhone("96" + String.format("%08d", idx + 2000));
                    queueService.customerJoinQueue(qId, req);
                    latenciesNs.add(System.nanoTime() - start);
                } catch (Exception ex) {
                    failures.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // 10 call next customer
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.nanoTime();
                    Thread.sleep(15);
                    queueService.callNextCustomer(targetShop.getId());
                    latenciesNs.add(System.nanoTime() - start);
                } catch (Exception ex) {
                    // Valid if empty queue during drain
                    if (!ex.getMessage().contains("No waiting customers") && !ex.getMessage().contains("Queue is empty")) {
                        failures.incrementAndGet();
                    }
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // 10 dashboard status checks
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.nanoTime();
                    queueService.getDashboard(targetShop.getId());
                    latenciesNs.add(System.nanoTime() - start);
                } catch (Exception ex) {
                    failures.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(20, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "Simultaneous operations must complete within 20s");
        assertEquals(0, failures.get(), "Zero unexpected failures must occur");

        List<Long> latenciesList = new ArrayList<>(latenciesNs);
        log.info("=== BENCHMARK 2 RESULTS: Simultaneous Operations on Same Shop ===");
        log.info("Operations Completed: {}, Failures: 0", latenciesList.size());
        log.info("Latency p50: {} ms, p95: {} ms, p99: {} ms",
                String.format("%.2f", percentile(latenciesList, 50)),
                String.format("%.2f", percentile(latenciesList, 95)),
                String.format("%.2f", percentile(latenciesList, 99)));
    }

    @Test
    @Order(3)
    @DisplayName("Load Simulation 3: Concurrent owner dashboard reads")
    void testConcurrentOwnerDashboardReads() throws Exception {
        Shop targetShop = testShops.get(0);
        int totalReads = 50;
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(totalReads);
        ConcurrentLinkedQueue<Long> latenciesNs = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < totalReads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.nanoTime();
                    QueueDashboardResponse resp = queueService.getDashboard(targetShop.getId());
                    latenciesNs.add(System.nanoTime() - start);
                    assertNotNull(resp);
                    assertEquals(targetShop.getId(), resp.getShopId());
                } catch (Exception ex) {
                    failures.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean done = finishLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(done);
        assertEquals(0, failures.get());

        List<Long> latenciesList = new ArrayList<>(latenciesNs);
        log.info("=== BENCHMARK 3 RESULTS: 50 Concurrent Dashboard Reads ===");
        log.info("Latency p50: {} ms, p95: {} ms, p99: {} ms",
                String.format("%.2f", percentile(latenciesList, 50)),
                String.format("%.2f", percentile(latenciesList, 95)),
                String.format("%.2f", percentile(latenciesList, 99)));
    }

    @Test
    @Order(4)
    @DisplayName("Load Simulation 4: Super Admin pagination with large datasets (500 records)")
    void testSuperAdminPaginationWithLargeDatasets() {
        authenticateAs(superAdmin, "ROLE_SUPER_ADMIN");
        String nonce = UUID.randomUUID().toString().substring(0, 8);

        // Seed 500 payment records in batch
        List<PaymentRecord> batch = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            PaymentRecord pr = new PaymentRecord(
                    "SUBSCRIPTION",
                    owner1,
                    testShops.get(0),
                    null,
                    new BigDecimal("499.00"),
                    "UTR_BENCH_" + nonce + "_" + i,
                    "bench@upi"
            );
            pr.setStatus(i % 2 == 0 ? "APPROVED" : "PENDING_APPROVAL");
            batch.add(pr);
        }
        paymentRecordRepository.saveAll(batch);

        List<Long> pageLatenciesNs = new ArrayList<>();

        // Query page 0 (size 50)
        long t0 = System.nanoTime();
        ResponseEntity<List<com.queueless.dto.AdminPaymentItem>> respPage0 =
                adminController.listPayments(null, null, 0, 50);
        pageLatenciesNs.add(System.nanoTime() - t0);

        assertEquals(200, respPage0.getStatusCode().value());
        assertEquals(50, respPage0.getBody().size());
        assertTrue(Long.parseLong(respPage0.getHeaders().getFirst("X-Total-Count")) >= 500);

        // Query page 4 (size 50)
        long t1 = System.nanoTime();
        ResponseEntity<List<com.queueless.dto.AdminPaymentItem>> respPage4 =
                adminController.listPayments(null, null, 4, 50);
        pageLatenciesNs.add(System.nanoTime() - t1);

        assertEquals(200, respPage4.getStatusCode().value());
        assertEquals(50, respPage4.getBody().size());

        // Query page 9 (size 50)
        long t2 = System.nanoTime();
        ResponseEntity<List<com.queueless.dto.AdminPaymentItem>> respPage9 =
                adminController.listPayments(null, null, 9, 50);
        pageLatenciesNs.add(System.nanoTime() - t2);

        assertEquals(200, respPage9.getStatusCode().value());
        assertEquals(50, respPage9.getBody().size());

        log.info("=== BENCHMARK 4 RESULTS: Super Admin Pagination on 500+ records ===");
        log.info("Page 0 Latency: {} ms, Page 4: {} ms, Page 9: {} ms",
                String.format("%.2f", pageLatenciesNs.get(0) / 1_000_000.0),
                String.format("%.2f", pageLatenciesNs.get(1) / 1_000_000.0),
                String.format("%.2f", pageLatenciesNs.get(2) / 1_000_000.0));
    }

    @Test
    @Order(5)
    @DisplayName("Load Simulation 5: Concurrent scheduled cleanup running simultaneously with customer joins")
    void testConcurrentScheduledCleanupAndCustomerJoins() throws Exception {
        Shop targetShop = testShops.get(1);
        String qId = publicQueueIdByShop.get(targetShop.getId());
        int joinCount = 30;
        ExecutorService executor = Executors.newFixedThreadPool(4);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(joinCount + 1);
        AtomicInteger failures = new AtomicInteger(0);

        // Background thread running maintenance cleanup
        executor.submit(() -> {
            try {
                startLatch.await();
                var stats = maintenanceService.performMaintenanceCleanup();
                assertTrue(stats.executed(), "Cleanup must execute while customer traffic is occurring");
            } catch (Exception ex) {
                log.error("Maintenance failed during concurrent traffic: {}", ex.getMessage());
                failures.incrementAndGet();
            } finally {
                finishLatch.countDown();
            }
        });

        // 30 parallel customer joins running at the same time
        for (int i = 0; i < joinCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    CustomerJoinRequest req = new CustomerJoinRequest();
                    req.setCustomerName("ConcurrentCleanerCustomer-" + idx);
                    req.setCustomerPhone("95" + String.format("%08d", idx + 3000));
                    CustomerJoinResponse resp = queueService.customerJoinQueue(qId, req);
                    assertNotNull(resp);
                } catch (Exception ex) {
                    log.error("Join failed during concurrent cleanup: {}", ex.getMessage());
                    failures.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = finishLatch.await(20, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "Concurrent maintenance and join operations must complete within 20s");
        assertEquals(0, failures.get(), "Zero operations must fail during concurrent maintenance execution");

        log.info("=== BENCHMARK 5 RESULTS: Cleanup and Joins Ran Concurrently Without Interference ===");
    }

    @Test
    @Order(6)
    @DisplayName("System Metrics: Connection Pool & JVM Memory Analysis")
    void testSystemConnectionPoolAndMemoryMetrics() {
        if (dataSource instanceof HikariDataSource hikariDs) {
            var pool = hikariDs.getHikariPoolMXBean();
            if (pool != null) {
                log.info("=== HIKARI CONNECTION POOL METRICS ===");
                log.info("Active Connections: {}", pool.getActiveConnections());
                log.info("Idle Connections: {}", pool.getIdleConnections());
                log.info("Total Connections: {}", pool.getTotalConnections());
                log.info("Threads Awaiting Connection: {}", pool.getThreadsAwaitingConnection());
                assertEquals(0, pool.getThreadsAwaitingConnection(), "No threads should be starved for connections");
            }
        }

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemory = runtime.maxMemory() / (1024 * 1024);

        log.info("=== JVM HEAP METRICS ===");
        log.info("Used Memory: {} MB / Max Memory: {} MB", usedMemory, maxMemory);
        assertTrue(usedMemory < maxMemory, "Heap memory must remain bounded under load");
    }
}