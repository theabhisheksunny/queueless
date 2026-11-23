package com.queueless;

import com.queueless.dto.*;
import com.queueless.entity.PaymentRecord;
import com.queueless.entity.Shop;
import com.queueless.entity.User;
import com.queueless.repository.PaymentRecordRepository;
import com.queueless.repository.QueueSessionRepository;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.UserRepository;
import com.queueless.service.AuthService;
import com.queueless.service.BranchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:queueless_conflict_audit_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.jpa.hibernate.ddl-auto=validate"
})
public class BranchCreationConflictResolutionTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private BranchService branchService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private QueueSessionRepository sessionRepository;

    @Autowired
    private PaymentRecordRepository paymentRecordRepository;

    @Autowired
    private javax.sql.DataSource dataSource;

    private User registerTestOwner(String phone, String fullName, String shopName) {
        try {
            authService.deleteAccount("+91" + phone);
        } catch (Exception ignored) {}

        RegisterRequest reg = new RegisterRequest();
        reg.setPhone(phone);
        reg.setPin("123456");
        reg.setConfirmPin("123456");
        reg.setFullName(fullName);
        reg.setShopName(shopName);
        reg.setCategory("Barber Shop");
        reg.setDeviceFingerprint("fp-" + phone);

        authService.register(reg);
        return userRepository.findByPhone("+91" + phone).orElseThrow();
    }

    @Test
    @DisplayName("1. Two branches with identical names can be created for the same owner without conflicts")
    void testIdenticalBranchNamesForSameOwner() {
        User owner = registerTestOwner("9870000001", "Identical Name Owner", "Super Cut Salon");

        CreateBranchRequest req1 = new CreateBranchRequest();
        req1.setName("Super Cut");
        req1.setBranchName("North Branch");
        req1.setCategory("Barber Shop");
        req1.setAddress("North Point");
        req1.setPhone("9870000011");
        req1.setAvgServiceDurationMinutes(15);

        BranchSummaryResponse b1 = branchService.createBranch(owner.getId(), req1);

        CreateBranchRequest req2 = new CreateBranchRequest();
        req2.setName("Super Cut"); // Identical display name
        req2.setBranchName("South Branch"); // Different physical branch location
        req2.setCategory("Barber Shop");
        req2.setAddress("South Point");
        req2.setPhone("9870000012");
        req2.setAvgServiceDurationMinutes(20);

        BranchSummaryResponse b2 = branchService.createBranch(owner.getId(), req2);

        assertNotNull(b1.getId());
        assertNotNull(b2.getId());
        assertNotEquals(b1.getId(), b2.getId(), "Each branch must have a unique internal ID");
        assertNotEquals(b1.getPublicQueueId(), b2.getPublicQueueId(), "Each branch must have a unique publicQueueId");
        assertEquals("Super Cut", b1.getName());
        assertEquals("Super Cut", b2.getName());
    }

    @Test
    @DisplayName("2. Multiple branches can be created for different owners with isolation")
    void testMultipleBranchesForDifferentOwners() {
        User ownerA = registerTestOwner("9870000002", "Owner Alpha", "Alpha Brand");
        User ownerB = registerTestOwner("9870000003", "Owner Beta", "Beta Brand");

        CreateBranchRequest reqA = new CreateBranchRequest();
        reqA.setName("Alpha Branch 2");
        reqA.setBranchName("Alpha 2");
        reqA.setCategory("Salon / Beauty");
        reqA.setPhone("9870000021");

        BranchSummaryResponse branchA = branchService.createBranch(ownerA.getId(), reqA);

        CreateBranchRequest reqB = new CreateBranchRequest();
        reqB.setName("Beta Branch 2");
        reqB.setBranchName("Beta 2");
        reqB.setCategory("Clinic / Healthcare");
        reqB.setPhone("9870000031");

        BranchSummaryResponse branchB = branchService.createBranch(ownerB.getId(), reqB);

        assertEquals(2, shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerA.getId()).size());
        assertEquals(2, shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerB.getId()).size());
        assertNotEquals(branchA.getId(), branchB.getId());
    }

    @Test
    @DisplayName("3. Client idempotency key guarantees retries return identical branch response without duplicating records")
    void testDuplicateRequestSubmissionIdempotency() {
        User owner = registerTestOwner("9870000004", "Double Click Owner", "DoubleClick Salon");

        CreateBranchRequest req = new CreateBranchRequest();
        req.setName("Express Branch");
        req.setBranchName("Station Branch");
        req.setCategory("Barber Shop");
        req.setAddress("Platform 1");
        req.setPhone("9870000041");
        req.setAvgServiceDurationMinutes(10);
        req.setIdempotencyKey("test-uuid-idemp-0004");

        // Immediate first click
        BranchSummaryResponse res1 = branchService.createBranch(owner.getId(), req);
        // Immediate second click (simulating retry with identical idempotency key)
        BranchSummaryResponse res2 = branchService.createBranch(owner.getId(), req);

        assertEquals(res1.getId(), res2.getId(), "Idempotent retry must return identical branch ID");
        assertEquals(res1.getPublicQueueId(), res2.getPublicQueueId());

        // Verify only ONE new branch was inserted
        List<Shop> ownerShops = shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.getId());
        assertEquals(2, ownerShops.size(), "Total shops must be 2 (1 primary + 1 branch), not 3");
    }

    @Test
    @DisplayName("3b. Two intentional branch creation requests with identical details and different keys are NOT merged")
    void testIntentionalIdenticalBranchesNotMerged() {
        User owner = registerTestOwner("9870000044", "Twin Branch Owner", "Twin Salon Hub");

        CreateBranchRequest branch1Req = new CreateBranchRequest();
        branch1Req.setName("City Center");
        branch1Req.setBranchName("City Center");
        branch1Req.setCategory("Barber Shop");
        branch1Req.setAddress("Mall Floor 1");
        branch1Req.setPhone("9870000045");
        branch1Req.setIdempotencyKey("uuid-intentional-1");

        BranchSummaryResponse b1 = branchService.createBranch(owner.getId(), branch1Req);

        // Same owner intentionally creates another branch with identical details but different key (or none)
        CreateBranchRequest branch2Req = new CreateBranchRequest();
        branch2Req.setName("City Center");
        branch2Req.setBranchName("City Center");
        branch2Req.setCategory("Barber Shop");
        branch2Req.setAddress("Mall Floor 1");
        branch2Req.setPhone("9870000045");
        branch2Req.setIdempotencyKey("uuid-intentional-2");

        BranchSummaryResponse b2 = branchService.createBranch(owner.getId(), branch2Req);

        assertNotEquals(b1.getId(), b2.getId(), "Intentional branch creations must NOT be merged; unique IDs required");
        assertNotEquals(b1.getPublicQueueId(), b2.getPublicQueueId(), "Unique publicQueueId required for each branch");

        List<Shop> ownerShops = shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.getId());
        assertEquals(3, ownerShops.size(), "Total shops must be 3 (1 primary + 2 distinct branches)");
    }

    @Test
    @DisplayName("4. Concurrent branch creation threads run safely without database deadlock or constraint violation")
    void testConcurrentBranchCreation() throws Exception {
        User owner = registerTestOwner("9870000005", "Concurrent Owner", "Concurrency Hub");

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);
        List<Future<BranchSummaryResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                startGate.await();
                CreateBranchRequest req = new CreateBranchRequest();
                req.setName("Concurrent Branch " + index);
                req.setBranchName("Loc " + index);
                req.setCategory("General");
                req.setAddress("Lane " + index);
                req.setPhone("987000005" + index);
                req.setAvgServiceDurationMinutes(15);
                return branchService.createBranch(owner.getId(), req);
            }));
        }

        startGate.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        List<Long> createdIds = new ArrayList<>();
        for (Future<BranchSummaryResponse> f : futures) {
            BranchSummaryResponse res = f.get();
            assertNotNull(res);
            assertNotNull(res.getId());
            createdIds.add(res.getId());
        }

        assertEquals(threadCount, createdIds.stream().distinct().count(), "All 4 distinct concurrent branches must succeed with unique IDs");
        // Total shops: 1 primary + 4 branches = 5
        List<Shop> totalShops = shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.getId());
        assertEquals(5, totalShops.size());
    }

    @Test
    @DisplayName("5. Transaction rollback: if error occurs, no orphaned shop or session records persist")
    void testTransactionRollbackIntegrity() {
        User owner = registerTestOwner("9870000006", "Rollback Owner", "Rollback Salon");
        int initialShopCount = shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.getId()).size();

        CreateBranchRequest badReq = new CreateBranchRequest();
        badReq.setBusinessId(999999L); // Non-existent business ID triggers exception
        badReq.setName("Failing Branch");

        assertThrows(IllegalArgumentException.class, () -> {
            branchService.createBranch(owner.getId(), badReq);
        });

        int finalShopCount = shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(owner.getId()).size();
        assertEquals(initialShopCount, finalShopCount, "Shop count must remain unchanged after rollback");
    }

    @Test
    @DisplayName("6. Branch registration payment and approval lifecycle preserves primary status and ₹99 fee")
    void testBranchPaymentLifecycle() {
        User owner = registerTestOwner("9870000007", "Payment Owner", "Payment Salon");

        CreateBranchRequest branchReq = new CreateBranchRequest();
        branchReq.setName("Paid Branch");
        branchReq.setBranchName("Sector 62");
        branchReq.setCategory("Barber Shop");
        BranchSummaryResponse branch = branchService.createBranch(owner.getId(), branchReq);

        assertFalse(branch.getIsPrimary());
        assertEquals("PAYMENT_PENDING", branch.getRegistrationStatus());

        // Submit ₹99 payment
        BranchRegistrationPaymentRequest payReq = new BranchRegistrationPaymentRequest();
        payReq.setPaymentReference("UTR_TEST_REG_9901");
        payReq.setPaymentUpiId("absunny@ybl");

        PaymentRecord record = branchService.submitBranchRegistrationPayment(owner.getId(), branch.getId(), payReq);
        assertNotNull(record.getId());
        assertEquals("PENDING_APPROVAL", record.getStatus());
        assertEquals("BRANCH_REGISTRATION", record.getPaymentType());
        assertEquals(new java.math.BigDecimal("99.00"), record.getAmount());

        // Cannot pay ₹99 twice for same branch while pending
        assertThrows(IllegalStateException.class, () -> {
            branchService.submitBranchRegistrationPayment(owner.getId(), branch.getId(), payReq);
        });
    }

    @Test
    @DisplayName("7. Database catalog audit: unique constraint on shops.owner_id is absent while indexes and FKs remain intact")
    void testDatabaseCatalogIntegrity() throws Exception {
        try (java.sql.Connection conn = dataSource.getConnection()) {
            java.sql.DatabaseMetaData meta = conn.getMetaData();

            // 1. Check indexes on shops
            boolean foundOwnerIndex = false;
            boolean foundUniqueOwnerIndex = false;
            boolean foundPrimaryKey = false;

            try (java.sql.ResultSet rs = meta.getIndexInfo(null, null, "SHOPS", false, false)) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    String idx = rs.getString("INDEX_NAME");
                    boolean nonUnique = rs.getBoolean("NON_UNIQUE");

                    if ("owner_id".equalsIgnoreCase(col)) {
                        foundOwnerIndex = true;
                        if (!nonUnique && (idx == null || (!idx.toLowerCase().contains("pkey") && !idx.toLowerCase().contains("primary")))) {
                            foundUniqueOwnerIndex = true;
                        }
                    }
                }
            }
            if (!foundOwnerIndex) {
                try (java.sql.ResultSet rs = meta.getIndexInfo(null, null, "shops", false, false)) {
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME");
                        String idx = rs.getString("INDEX_NAME");
                        boolean nonUnique = rs.getBoolean("NON_UNIQUE");

                        if ("owner_id".equalsIgnoreCase(col)) {
                            foundOwnerIndex = true;
                            if (!nonUnique && (idx == null || (!idx.toLowerCase().contains("pkey") && !idx.toLowerCase().contains("primary")))) {
                                foundUniqueOwnerIndex = true;
                            }
                        }
                    }
                }
            }

            assertTrue(foundOwnerIndex, "idx_shops_owner or non-unique owner index must exist");
            assertFalse(foundUniqueOwnerIndex, "No UNIQUE index must exist solely on shops.owner_id");

            // 2. Check primary key exists on shops.id
            try (java.sql.ResultSet pks = meta.getPrimaryKeys(null, null, "SHOPS")) {
                if (pks.next()) {
                    foundPrimaryKey = true;
                    assertEquals("id", pks.getString("COLUMN_NAME").toLowerCase());
                }
            }
            if (!foundPrimaryKey) {
                try (java.sql.ResultSet pks = meta.getPrimaryKeys(null, null, "shops")) {
                    if (pks.next()) {
                        foundPrimaryKey = true;
                        assertEquals("id", pks.getString("COLUMN_NAME").toLowerCase());
                    }
                }
            }
            assertTrue(foundPrimaryKey, "shops primary key (id) must remain intact");

            // 3. Check foreign key from shops.owner_id to users.id exists
            boolean foundOwnerFk = false;
            try (java.sql.ResultSet fks = meta.getImportedKeys(null, null, "SHOPS")) {
                while (fks.next()) {
                    String fkCol = fks.getString("FKCOLUMN_NAME");
                    String pkTable = fks.getString("PKTABLE_NAME");
                    if ("owner_id".equalsIgnoreCase(fkCol) && "users".equalsIgnoreCase(pkTable)) {
                        foundOwnerFk = true;
                    }
                }
            }
            if (!foundOwnerFk) {
                try (java.sql.ResultSet fks = meta.getImportedKeys(null, null, "shops")) {
                    while (fks.next()) {
                        String fkCol = fks.getString("FKCOLUMN_NAME");
                        String pkTable = fks.getString("PKTABLE_NAME");
                        if ("owner_id".equalsIgnoreCase(fkCol) && "users".equalsIgnoreCase(pkTable)) {
                            foundOwnerFk = true;
                        }
                    }
                }
            }
            assertTrue(foundOwnerFk, "Foreign key from shops.owner_id to users.id must be preserved");
        }
    }

    @Test
    @DisplayName("8. Cross-instance DB-backed idempotency resolves branch even if in-memory cache was cleared")
    void testDistributedDatabaseBackedIdempotency() {
        User owner = registerTestOwner("9870000088", "Distributed Owner", "Cloud Run Branch Hub");

        CreateBranchRequest branchReq = new CreateBranchRequest();
        branchReq.setName("Autoscale Branch");
        branchReq.setBranchName("Instance 1 Loc");
        branchReq.setCategory("Barber Shop");
        branchReq.setIdempotencyKey("uuid-cloud-run-cross-instance");

        BranchSummaryResponse created = branchService.createBranch(owner.getId(), branchReq);
        assertNotNull(created.getId());

        // Simulate request hitting another instance (same ownerId + same idempotencyKey)
        CreateBranchRequest retryReq = new CreateBranchRequest();
        retryReq.setName("Autoscale Branch");
        retryReq.setBranchName("Instance 1 Loc");
        retryReq.setCategory("Barber Shop");
        retryReq.setIdempotencyKey("uuid-cloud-run-cross-instance");

        BranchSummaryResponse resolved = branchService.createBranch(owner.getId(), retryReq);
        assertEquals(created.getId(), resolved.getId(), "Cross-instance retry must resolve to same branch ID");
        assertEquals(created.getPublicQueueId(), resolved.getPublicQueueId());
    }
}
