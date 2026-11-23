package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.SubscriptionPaymentSubmitRequest;
import com.queueless.dto.SubscriptionStatusResponse;
import com.queueless.entity.*;
import com.queueless.repository.*;
import com.queueless.service.BranchService;
import com.queueless.service.CategoryChangeService;
import com.queueless.service.SeatService;
import com.queueless.service.SubscriptionService;
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
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SuperAdminPaymentVerificationWorkflowTest {

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
    private SubscriptionSeatRepository seatRepository;

    @Autowired
    private BusinessCategoryChangeRequestRepository categoryChangeRequestRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private Clock clock;

    @Autowired
    private com.queueless.security.JwtTokenProvider tokenProvider;

    private User testSuperAdmin;
    private String superAdminToken;
    private User testOwner;
    private Business testBusiness;
    private Shop testShop;

    @BeforeEach
    void setUp() {
        long ts = System.currentTimeMillis() % 10000000;
        String phone = "+9198" + String.format("%08d", ts);
        String email = "owner_" + ts + "_" + System.nanoTime() + "@queueless.in";
        testOwner = new User(
                email,
                passwordEncoder.encode("Password123!"),
                "VerifyOwner" + ts,
                phone,
                UserRole.ROLE_OWNER
        );
        testOwner.setStatus("ACTIVE");
        testOwner = userRepository.save(testOwner);

        testBusiness = new Business(testOwner, "Verification Enterprises " + ts);
        testBusiness = businessRepository.save(testBusiness);

        testShop = new Shop(testOwner, "Primary Flagship Branch", "RETAIL", 25);
        testShop.setBusiness(testBusiness);
        testShop.setIsPrimary(true);
        testShop.setRegistrationStatus("REGISTERED");
        testShop = shopRepository.save(testShop);

        testSuperAdmin = new User(
                "superadmin_" + ts + "_" + System.nanoTime() + "@queueless.in",
                passwordEncoder.encode("SuperPass!123"),
                "Platform Super Admin",
                "+9197" + String.format("%08d", ts),
                UserRole.ROLE_SUPER_ADMIN
        );
        testSuperAdmin.setStatus("ACTIVE");
        testSuperAdmin.setActiveSessionId(java.util.UUID.randomUUID().toString());
        testSuperAdmin = userRepository.save(testSuperAdmin);
        superAdminToken = tokenProvider.generateTokenForUsername(testSuperAdmin.getEmail(), testSuperAdmin.getActiveSessionId());
    }

    @Test
    @DisplayName("1. Subscription Approval: Seamlessly extends active subscription and resolves pending row")
    void approvePayment_Subscription_ExtendsActiveAndResolvesPending() throws Exception {
        LocalDate today = LocalDate.now(clock);
        LocalDate existingEnd = today.plusDays(10);

        // Pre-existing active subscription
        Subscription activeSub = new Subscription(
                testShop,
                SubscriptionPlan.MONTHLY,
                SubscriptionStatus.ACTIVE,
                today.minusDays(20),
                existingEnd,
                new BigDecimal("51.00")
        );
        subscriptionRepository.save(activeSub);

        // Owner submits renewal payment
        String utr = "UTR" + System.currentTimeMillis();
        Subscription pendingSub = new Subscription(
                testOwner,
                testShop,
                SubscriptionPlan.MONTHLY,
                SubscriptionStatus.PENDING_APPROVAL,
                today,
                today,
                new BigDecimal("51.00")
        );
        pendingSub.setPaymentReference(utr);
        subscriptionRepository.save(pendingSub);

        PaymentRecord payRec = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                utr,
                "absunny@ybl"
        );
        payRec.setPlanType("MONTHLY");
        payRec = paymentRecordRepository.save(payRec);

        // Super Admin approves payment
        mockMvc.perform(post("/api/admin/payments/" + payRec.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("Payment approved successfully")));

        // Verify PaymentRecord is APPROVED
        PaymentRecord updatedPay = paymentRecordRepository.findById(payRec.getId()).orElseThrow();
        assertEquals("APPROVED", updatedPay.getStatus());
        assertNotNull(updatedPay.getReviewedAt());
        assertTrue(updatedPay.getNotes().contains("Manually verified"));

        // Verify Pending Subscription was updated to ACTIVE with extended dates (existingEnd + 30 days)
        Subscription resolvedSub = subscriptionRepository.findById(pendingSub.getId()).orElseThrow();
        assertEquals(SubscriptionStatus.ACTIVE, resolvedSub.getStatus());
        assertEquals(existingEnd.plusDays(30), resolvedSub.getEndDate());

        // Verify getShopSubscriptionStatus sees NO pending payment and extended active days
        var statusResp = subscriptionService.getShopSubscriptionStatus(testShop.getId());
        assertTrue(statusResp.isActive());
        assertNull(statusResp.getPendingPaymentReference(), "Pending payment reference should be cleared");
        assertEquals(40, statusResp.getDaysRemaining());

        // Verify immutable AuditLog
        boolean auditLogged = auditLogRepository.findAll().stream()
                .anyMatch(a -> "APPROVE_PAYMENT".equals(a.getAction()) && a.getDetails().contains(utr));
        assertTrue(auditLogged, "Expected APPROVE_PAYMENT audit log entry");
    }

    @Test
    @DisplayName("2. Branch Registration Approval: Activates only target branch and marks fee paid")
    void approvePayment_BranchRegistration_SingleAndBulk() throws Exception {
        // Additional physical branch
        Shop branch2 = new Shop(testOwner, "Koramangala Branch", "RETAIL", 20);
        branch2.setBusiness(testBusiness);
        branch2.setRegistrationStatus("PENDING_PAYMENT");
        branch2 = shopRepository.save(branch2);

        String utr = "UTR_BRANCH_" + System.currentTimeMillis();
        PaymentRecord branchPay = new PaymentRecord(
                "BRANCH_REGISTRATION",
                testOwner,
                branch2,
                null,
                BranchService.BRANCH_REGISTRATION_FEE,
                utr,
                "absunny@ybl"
        );
        branchPay.setBatchCount(1);
        branchPay = paymentRecordRepository.save(branchPay);

        mockMvc.perform(post("/api/admin/payments/" + branchPay.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        Shop verifiedBranch = shopRepository.findById(branch2.getId()).orElseThrow();
        assertEquals("REGISTERED", verifiedBranch.getRegistrationStatus());
        assertEquals(new BigDecimal("99.00"), verifiedBranch.getRegistrationFeePaid());
    }

    @Test
    @DisplayName("3. User Seat Approval: Activates additional staff seat for correct owner")
    void approvePayment_UserSeat_ActivatesEntitlement() throws Exception {
        String utr = "UTR_SEAT_" + System.currentTimeMillis();
        PaymentRecord seatPay = new PaymentRecord(
                "USER_SEAT",
                testOwner,
                null,
                null,
                SeatService.SEAT_MONTHLY_PRICE,
                utr,
                "absunny@ybl"
        );
        seatPay.setPlanType("MONTHLY");
        seatPay = paymentRecordRepository.save(seatPay);

        mockMvc.perform(post("/api/admin/payments/" + seatPay.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        PaymentRecord updatedPay = paymentRecordRepository.findById(seatPay.getId()).orElseThrow();
        assertNotNull(updatedPay.getSeat(), "PaymentRecord should be linked to created SubscriptionSeat");

        SubscriptionSeat seat = seatRepository.findById(updatedPay.getSeat().getId()).orElseThrow();
        assertEquals(SubscriptionStatus.ACTIVE, seat.getStatus());
        assertEquals(testOwner.getId(), seat.getOwner().getId());
        assertEquals(LocalDate.now(clock).plusDays(30), seat.getEndDate());
    }

    @Test
    @DisplayName("4. Category Change Approval: Updates shop category and logs category change audit")
    void approvePayment_CategoryChange_UpdatesShopCategory() throws Exception {
        String utr = "UTR_CAT_" + System.currentTimeMillis();
        BusinessCategoryChangeRequest catReq = new BusinessCategoryChangeRequest(
                testShop.getId(),
                testOwner.getId(),
                "RETAIL",
                "SALON"
        );
        catReq.setPaymentReference(utr);
        catReq.setStatus(CategoryChangeStatus.PENDING_VERIFICATION);
        catReq = categoryChangeRequestRepository.save(catReq);

        PaymentRecord catPay = new PaymentRecord(
                "CATEGORY_CHANGE",
                testOwner,
                testShop,
                null,
                new BigDecimal("101.00"),
                utr,
                "absunny@ybl"
        );
        catPay = paymentRecordRepository.save(catPay);

        mockMvc.perform(post("/api/admin/payments/" + catPay.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        Shop updatedShop = shopRepository.findById(testShop.getId()).orElseThrow();
        assertEquals("SALON", updatedShop.getCategory(), "Shop category should be updated to SALON");

        BusinessCategoryChangeRequest updatedReq = categoryChangeRequestRepository.findById(catReq.getId()).orElseThrow();
        assertEquals(CategoryChangeStatus.APPROVED, updatedReq.getStatus());
    }

    @Test
    @DisplayName("5. Amount Mismatch: Reject approval if payment amount does not match expected pricing")
    void amountMismatch_RejectsApproval() throws Exception {
        String utr = "UTR_MISMATCH_" + System.currentTimeMillis();
        // Monthly subscription expects 51.00, user paid 50.00
        PaymentRecord badPay = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("50.00"),
                utr,
                "absunny@ybl"
        );
        badPay.setPlanType("MONTHLY");
        badPay = paymentRecordRepository.save(badPay);

        mockMvc.perform(post("/api/admin/payments/" + badPay.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Payment amount mismatch")));

        // Record should remain PENDING_APPROVAL
        PaymentRecord unchanged = paymentRecordRepository.findById(badPay.getId()).orElseThrow();
        assertEquals("PENDING_APPROVAL", unchanged.getStatus());
    }

    @Test
    @DisplayName("6. Duplicate UTR Policy: Rejects approval if UTR is already approved on another record")
    void duplicateUtr_RejectsApproval() throws Exception {
        String sharedUtr = "UTR_DUPLICATE_" + System.currentTimeMillis() + "_" + System.nanoTime();

        // Pre-existing approved payment with shared UTR
        PaymentRecord approvedPay = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                sharedUtr,
                "absunny@ybl"
        );
        approvedPay.setStatus("APPROVED");
        paymentRecordRepository.save(approvedPay);

        // Second payment attempt with same UTR
        PaymentRecord duplicatePay = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                sharedUtr,
                "absunny@ybl"
        );
        duplicatePay.setPlanType("MONTHLY");
        duplicatePay = paymentRecordRepository.save(duplicatePay);

        mockMvc.perform(post("/api/admin/payments/" + duplicatePay.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("already been approved")));

        PaymentRecord unchanged = paymentRecordRepository.findById(duplicatePay.getId()).orElseThrow();
        assertEquals("PENDING_APPROVAL", unchanged.getStatus());
    }

    @Test
    @DisplayName("7. Duplicate Approval Idempotency: Second approval returns 200 without duplicate entitlement")
    void duplicateApproval_IsIdempotent() throws Exception {
        String utr = "UTR_IDEMPOTENT_" + System.currentTimeMillis();
        PaymentRecord pay = new PaymentRecord(
                "BRANCH_REGISTRATION",
                testOwner,
                testShop,
                null,
                new BigDecimal("99.00"),
                utr,
                "absunny@ybl"
        );
        pay = paymentRecordRepository.save(pay);

        // First approval
        mockMvc.perform(post("/api/admin/payments/" + pay.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        // Second approval
        mockMvc.perform(post("/api/admin/payments/" + pay.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("already been approved")));
    }

    @Test
    @DisplayName("8. Payment Rejection: Mandatory reason, preserves record, clears pending sub, does not activate entitlement")
    void rejectPayment_RequiresReason_ClearsPendingAndPreservesRecord() throws Exception {
        // Pending branch
        Shop branch2 = new Shop(testOwner, "Indiranagar Branch", "RETAIL", 20);
        branch2.setBusiness(testBusiness);
        branch2.setRegistrationStatus("PENDING_PAYMENT");
        branch2 = shopRepository.save(branch2);

        String utr = "UTR_REJECT_" + System.currentTimeMillis();
        PaymentRecord pay = new PaymentRecord(
                "BRANCH_REGISTRATION",
                testOwner,
                branch2,
                null,
                new BigDecimal("99.00"),
                utr,
                "absunny@ybl"
        );
        pay = paymentRecordRepository.save(pay);

        // 1. Attempt rejection without reason -> 400 Bad Request
        mockMvc.perform(post("/api/admin/payments/" + pay.getId() + "/reject")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Rejection reason is required")));

        // 2. Reject with valid reason
        mockMvc.perform(post("/api/admin/payments/" + pay.getId() + "/reject")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "UTR not found in bank statement"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("Payment rejected")));

        // 3. Verify PaymentRecord is REJECTED with reason and reviewedBy
        PaymentRecord rejectedPay = paymentRecordRepository.findById(pay.getId()).orElseThrow();
        assertEquals("REJECTED", rejectedPay.getStatus());
        assertEquals("UTR not found in bank statement", rejectedPay.getRejectionReason());
        assertNotNull(rejectedPay.getReviewedAt());

        // 4. Verify branch is NOT activated (remains PENDING_PAYMENT)
        Shop untouchedBranch = shopRepository.findById(branch2.getId()).orElseThrow();
        assertEquals("PENDING_PAYMENT", untouchedBranch.getRegistrationStatus());

        // 5. Verify audit log
        boolean auditLogged = auditLogRepository.findAll().stream()
                .anyMatch(a -> "REJECT_PAYMENT".equals(a.getAction()) && a.getDetails().contains("UTR not found"));
        assertTrue(auditLogged, "Expected REJECT_PAYMENT audit log entry");
    }

    @Test
    @DisplayName("9. Subscription Rejection: Clears pending payment so owner can resubmit corrected payment")
    void subscriptionRejection_EnablesOwnerCorrectionResubmission() throws Exception {
        String utr = "UTR_SUB_REJECT_" + System.currentTimeMillis();
        Subscription pendingSub = new Subscription(
                testOwner,
                testShop,
                SubscriptionPlan.MONTHLY,
                SubscriptionStatus.PENDING_APPROVAL,
                LocalDate.now(clock),
                LocalDate.now(clock),
                new BigDecimal("51.00")
        );
        pendingSub.setPaymentReference(utr);
        subscriptionRepository.save(pendingSub);

        PaymentRecord pay = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                utr,
                "absunny@ybl"
        );
        pay.setPlanType("MONTHLY");
        pay = paymentRecordRepository.save(pay);

        // Reject payment
        mockMvc.perform(post("/api/admin/payments/" + pay.getId() + "/reject")
                        .header("Authorization", "Bearer " + superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "Payment reference invalid"))))
                .andExpect(status().isOk());

        // Owner queries status -> pending reference should be cleared
        var statusResp = subscriptionService.getShopSubscriptionStatus(testShop.getId());
        assertNull(statusResp.getPendingPaymentReference(), "Owner should no longer see pending UTR");

        // Owner can now submit a corrected payment with a new UTR
        String correctedUtr = "UTR_CORRECTED_" + System.currentTimeMillis();
        SubscriptionPaymentSubmitRequest req = new SubscriptionPaymentSubmitRequest();
        req.setPlanType(SubscriptionPlan.MONTHLY);
        req.setPaymentReference(correctedUtr);
        req.setPaymentUpiId("absunny@ybl");
        SubscriptionStatusResponse resubmitted = subscriptionService.submitPayment(testShop.getId(), req);

        assertEquals(correctedUtr, resubmitted.getPendingPaymentReference());
    }

    @Test
    @DisplayName("10. Security: Non-admin users and legacy X-Admin-Key cannot access approval or rejection endpoints (403 Forbidden)")
    void unauthorizedUser_CannotApproveOrReject() throws Exception {
        String utr = "UTR_SEC_" + System.currentTimeMillis();
        PaymentRecord pay = new PaymentRecord(
                "SUBSCRIPTION",
                testOwner,
                testShop,
                null,
                new BigDecimal("51.00"),
                utr,
                "absunny@ybl"
        );
        pay = paymentRecordRepository.save(pay);

        // 1. No key or JWT -> 403
        mockMvc.perform(post("/api/admin/payments/" + pay.getId() + "/approve"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/payments/" + pay.getId() + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "test"))))
                .andExpect(status().isForbidden());

        // 2. Legacy X-Admin-Key (ROLE_ADMIN automation) -> strictly 403 Forbidden
        mockMvc.perform(post("/api/admin/payments/" + pay.getId() + "/approve")
                        .header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/admin/payments/" + pay.getId() + "/reject")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "test"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }
}
