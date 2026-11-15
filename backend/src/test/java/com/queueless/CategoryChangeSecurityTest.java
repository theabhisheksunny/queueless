package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.repository.BusinessCategoryChangeAuditRepository;
import com.queueless.repository.BusinessCategoryChangeRequestRepository;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.SubscriptionRepository;
import com.queueless.service.AuthService;
import com.queueless.service.CategoryChangeService;
import com.queueless.service.ShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CategoryChangeSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private ShopService shopService;

    @Autowired
    private CategoryChangeService categoryChangeService;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private BusinessCategoryChangeRequestRepository requestRepository;

    @Autowired
    private BusinessCategoryChangeAuditRepository auditRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private com.queueless.repository.UserRepository userRepository;

    @Value("${app.admin.key:admin-secret-queueless-2026}")
    private String adminKey;

    private Long shopId;
    private Long ownerId;
    private String ownerToken;
    private String ownerEmail;

    @BeforeEach
    void setUp() {
        long ts = System.currentTimeMillis() + (int)(Math.random() * 100000);
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Ramesh Barber");
        ownerEmail = "ramesh_" + ts + "@barbershop.com";
        req.setEmail(ownerEmail);
        req.setPassword("testPassword123");
        req.setPhone("98" + (10000000 + (int)(Math.random() * 89999999)));
        req.setOtp("123456");
        req.setShopName("Ramesh Classic Hair Salon");
        req.setCategory("Barber Shop");

        var auth = authService.register(req);
        shopId = auth.getShopId();
        ownerId = userRepository.findByEmail(auth.getEmail()).orElseThrow().getId();
        ownerToken = auth.getToken();
    }

    @Test
    @DisplayName("1. Ordinary shop update cannot change category; direct API category modification is rejected")
    void testOrdinaryShopUpdateCannotChangeCategory() throws Exception {
        // 1. Changing name and duration succeeds
        ShopSettingsRequest validUpdate = new ShopSettingsRequest("Ramesh Modern Salon", 20);
        mockMvc.perform(put("/api/owner/settings")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ramesh Modern Salon"))
                .andExpect(jsonPath("$.avgServiceDurationMinutes").value(20))
                .andExpect(jsonPath("$.category").value("Barber Shop"));

        // 2. Direct attempt to modify category via PUT /api/owner/settings is rejected with 400 Bad Request
        ShopSettingsRequest maliciousUpdate = new ShopSettingsRequest();
        maliciousUpdate.setName("Ramesh Modern Salon");
        maliciousUpdate.setCategory("Clinic & Doctor"); // Attempt to bypass locked category
        maliciousUpdate.setAvgServiceDurationMinutes(20);

        mockMvc.perform(put("/api/owner/settings")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(maliciousUpdate)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Business category is locked after registration")));

        // 3. Service layer also rejects
        assertThatThrownBy(() -> shopService.updateShopSettings(shopId, maliciousUpdate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Business category is locked after registration");

        // 4. Verify shop category in database is still "Barber Shop"
        Shop shop = shopRepository.findById(shopId).orElseThrow();
        assertThat(shop.getCategory()).isEqualTo("Barber Shop");
    }

    @Test
    @DisplayName("2. Category change request validations: identical category, unsupported category, fee = 101, single pending request")
    void testCategoryChangeRequestValidation() throws Exception {
        // 1. Requesting identical category is rejected
        CategoryChangeInitiateRequest identicalReq = new CategoryChangeInitiateRequest("Barber Shop");
        mockMvc.perform(post("/api/owner/category-change/request")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(identicalReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("must be different from current registered category")));

        // 2. Requesting unsupported category is rejected
        CategoryChangeInitiateRequest invalidReq = new CategoryChangeInitiateRequest("Crypto Mining Farm");
        mockMvc.perform(post("/api/owner/category-change/request")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("not a supported business category")));

        // 3. Requesting valid category creates request with fixed fee ₹101
        CategoryChangeInitiateRequest validReq = new CategoryChangeInitiateRequest("Salon & Spa");
        mockMvc.perform(post("/api/owner/category-change/request")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").value(101.00))
                .andExpect(jsonPath("$.requestedCategory").value("Salon & Spa"))
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.hasPendingRequest").value(true));

        // 4. Attempting a second request while first is pending is prevented (returns 409 Conflict)
        CategoryChangeInitiateRequest secondReq = new CategoryChangeInitiateRequest("Clinic & Doctor");
        mockMvc.perform(post("/api/owner/category-change/request")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("already awaiting payment or verification")));

        // 5. Category remains unchanged while payment is pending
        Shop shop = shopRepository.findById(shopId).orElseThrow();
        assertThat(shop.getCategory()).isEqualTo("Barber Shop");
    }

    @Test
    @DisplayName("3. Payment submission integrity: UTR format validation, duplicate UTR prevention, category remains unchanged")
    void testPaymentSubmissionAndUtrIntegrity() throws Exception {
        // Initiate request
        var initRes = categoryChangeService.initiateRequest(shopId, ownerId, new CategoryChangeInitiateRequest("Clinic & Doctor"));
        Long requestId = initRes.getRequestId();

        // 1. Invalid UTR format (too short)
        CategoryChangePaymentSubmitRequest invalidUtrReq = new CategoryChangePaymentSubmitRequest(requestId, "123");
        mockMvc.perform(post("/api/owner/category-change/payment")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidUtrReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Invalid UTR reference format")));

        // 2. Pre-seed a subscription with UTR "SUB_UTR_998877"
        Shop shop = shopRepository.findById(shopId).orElseThrow();
        Subscription existingSub = new Subscription(shop, SubscriptionPlan.MONTHLY, SubscriptionStatus.ACTIVE,
                LocalDate.now(), LocalDate.now().plusDays(30), new BigDecimal("51.00"));
        existingSub.setPaymentReference("SUB_UTR_998877");
        subscriptionRepository.save(existingSub);

        // Attempt to reuse subscription UTR for category change -> rejected
        CategoryChangePaymentSubmitRequest reuseSubUtr = new CategoryChangePaymentSubmitRequest(requestId, "SUB_UTR_998877");
        mockMvc.perform(post("/api/owner/category-change/payment")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reuseSubUtr)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("already registered for a subscription payment")));

        // 3. Submit valid unique UTR
        String validUtr = "CAT_UTR_" + System.currentTimeMillis();
        CategoryChangePaymentSubmitRequest validPayment = new CategoryChangePaymentSubmitRequest(requestId, validUtr);
        mockMvc.perform(post("/api/owner/category-change/payment")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayment)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.paymentReference").value(validUtr));

        // 4. Shop category MUST remain "Barber Shop" before admin approval!
        Shop shopAfterPayment = shopRepository.findById(shopId).orElseThrow();
        assertThat(shopAfterPayment.getCategory()).isEqualTo("Barber Shop");
    }

    @Test
    @DisplayName("4. Admin approval: authorization checks, category update, immutable audit log, prevents double approval")
    void testAdminApprovalWorkflow() throws Exception {
        // Initiate and submit payment
        var initRes = categoryChangeService.initiateRequest(shopId, ownerId, new CategoryChangeInitiateRequest("Small Hospital"));
        Long requestId = initRes.getRequestId();
        String utr = "ADMIN_TEST_UTR_" + System.currentTimeMillis();
        categoryChangeService.submitPayment(shopId, ownerId, new CategoryChangePaymentSubmitRequest(requestId, utr));

        // 1. Unauthorized attempt to approve without Admin Key -> 403 Forbidden
        mockMvc.perform(post("/api/admin/category-requests/" + requestId + "/approve"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/category-requests/" + requestId + "/approve")
                        .header("X-Admin-Key", "wrong-key"))
                .andExpect(status().isForbidden());

        // 2. Self-approval attempt by owner is rejected
        assertThatThrownBy(() -> categoryChangeService.adminApproveRequest(requestId, ownerEmail))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("Shop owner cannot self-approve");

        // 3. Authorized Admin approves
        mockMvc.perform(post("/api/admin/category-requests/" + requestId + "/approve")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Category change request approved successfully."));

        // 4. Verify shop category is updated in database
        Shop shop = shopRepository.findById(shopId).orElseThrow();
        assertThat(shop.getCategory()).isEqualTo("Small Hospital");

        // 5. Verify immutable audit log record created
        var audits = auditRepository.findByShopIdOrderByApprovedAtDesc(shopId);
        assertThat(audits).hasSize(1);
        var audit = audits.get(0);
        assertThat(audit.getOldCategory()).isEqualTo("Barber Shop");
        assertThat(audit.getNewCategory()).isEqualTo("Small Hospital");
        assertThat(audit.getAmountPaid()).isEqualByComparingTo(new BigDecimal("101.00"));
        assertThat(audit.getRequestId()).isEqualTo(requestId);
        assertThat(audit.getApprovedBy()).isEqualTo("admin");

        // 6. Cannot approve twice (returns 409 Conflict)
        mockMvc.perform(post("/api/admin/category-requests/" + requestId + "/approve")
                        .header("X-Admin-Key", adminKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("already been approved")));

        // 7. Verify trial dates and subscriptions were not altered
        Subscription sub = subscriptionRepository.findTopByShopIdOrderByEndDateDesc(shopId).orElseThrow();
        assertThat(sub.getTrialStartedAt()).isNotNull();
        assertThat(sub.getTrialEndsAt()).isNotNull();
    }

    @Test
    @DisplayName("5. Admin rejection: original category unchanged, reason recorded, owner can submit new request")
    void testAdminRejectionWorkflow() throws Exception {
        var initRes = categoryChangeService.initiateRequest(shopId, ownerId, new CategoryChangeInitiateRequest("Repair Shop"));
        Long requestId = initRes.getRequestId();
        String utr = "REJECT_UTR_" + System.currentTimeMillis();
        categoryChangeService.submitPayment(shopId, ownerId, new CategoryChangePaymentSubmitRequest(requestId, utr));

        // Admin rejects request with reason
        AdminCategoryRejectRequest rejectReq = new AdminCategoryRejectRequest("UTR not found in bank statement.");
        mockMvc.perform(post("/api/admin/category-requests/" + requestId + "/reject")
                        .header("X-Admin-Key", adminKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Category change request rejected."));

        // Verify shop category is UNCHANGED
        Shop shop = shopRepository.findById(shopId).orElseThrow();
        assertThat(shop.getCategory()).isEqualTo("Barber Shop");

        // Verify request is REJECTED
        var req = requestRepository.findById(requestId).orElseThrow();
        assertThat(req.getStatus()).isEqualTo(CategoryChangeStatus.REJECTED);
        assertThat(req.getRejectionReason()).isEqualTo("UTR not found in bank statement.");

        // Owner can now submit a new request
        var newReq = categoryChangeService.initiateRequest(shopId, ownerId, new CategoryChangeInitiateRequest("Restaurant / Cafe"));
        assertThat(newReq.getRequestedCategory()).isEqualTo("Restaurant / Cafe");
        assertThat(newReq.getStatus()).isEqualTo(CategoryChangeStatus.PAYMENT_PENDING);
    }

    @Test
    @DisplayName("6. Duplicate shop names operate independently with isolated category change workflows")
    void testDuplicateShopNamesIndependentCategoryChanges() throws Exception {
        String sharedName = "City Express Hub";

        // Shop 1 registration
        RegisterRequest req1 = new RegisterRequest();
        req1.setFullName("Owner A");
        req1.setEmail("ownerA_" + System.currentTimeMillis() + "@hub.com");
        req1.setPassword("pass123");
        req1.setPhone("98" + (10000000 + (int)(Math.random() * 89999999)));
        req1.setOtp("123456");
        req1.setShopName(sharedName);
        req1.setCategory("Barber Shop");
        var auth1 = authService.register(req1);

        // Shop 2 registration with identical shop name
        RegisterRequest req2 = new RegisterRequest();
        req2.setFullName("Owner B");
        req2.setEmail("ownerB_" + System.currentTimeMillis() + "@hub.com");
        req2.setPassword("pass123");
        req2.setPhone("98" + (10000000 + (int)(Math.random() * 89999999)));
        req2.setOtp("123456");
        req2.setShopName(sharedName);
        req2.setCategory("Barber Shop");
        var auth2 = authService.register(req2);

        Long owner1Id = userRepository.findByEmail(auth1.getEmail()).orElseThrow().getId();

        // Shop 1 initiates and completes category change to "Automobile Service"
        var init1 = categoryChangeService.initiateRequest(auth1.getShopId(), owner1Id,
                new CategoryChangeInitiateRequest("Automobile Service"));
        categoryChangeService.submitPayment(auth1.getShopId(), owner1Id,
                new CategoryChangePaymentSubmitRequest(init1.getRequestId(), "UTR_AUTO_" + System.currentTimeMillis()));
        categoryChangeService.adminApproveRequest(init1.getRequestId(), "admin");

        // Shop 1 category is updated to "Automobile Service"
        Shop shop1 = shopRepository.findById(auth1.getShopId()).orElseThrow();
        assertThat(shop1.getCategory()).isEqualTo("Automobile Service");

        // Shop 2 category is completely untouched and remains "Barber Shop"
        Shop shop2 = shopRepository.findById(auth2.getShopId()).orElseThrow();
        assertThat(shop2.getCategory()).isEqualTo("Barber Shop");
    }
}
