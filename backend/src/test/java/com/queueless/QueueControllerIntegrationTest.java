package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.*;
import com.queueless.entity.CustomerQueueStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class QueueControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestRegistrationHelper testRegistrationHelper;

    private String ownerJwtToken;
    private Long shopId;

    @BeforeEach
    void setUp() throws Exception {
        // Register unique shop owner
        String uniqueEmail = "integration" + System.currentTimeMillis() + "@queueless.com";
        String uniquePhone = "98" + (10000000 + (int)(Math.random() * 89999999));
        RegisterRequest registerReq = new RegisterRequest();
        registerReq.setFullName("Integration Owner");
        registerReq.setEmail(uniqueEmail);
        registerReq.setPassword("password123");
        registerReq.setPhone(uniquePhone);
        testRegistrationHelper.preparePin(registerReq);
        registerReq.setShopName("Elite Barber & Spa");
        registerReq.setCategory("Barber Shop");
        registerReq.setAvgServiceDurationMinutes(12);

        String regJson = objectMapper.writeValueAsString(registerReq);

        var regResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regJson))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(regResult.getResponse().getContentAsString(), AuthResponse.class);
        ownerJwtToken = auth.getToken();
        shopId = auth.getShopId();
    }

    @Test
    @DisplayName("Complete E2E Owner Flow: Start Queue, Manual Add, Call Next, Serve, Complete, Close")
    void testCompleteOwnerAndCustomerFlow() throws Exception {
        // 1. Start Queue
        var startResult = mockMvc.perform(post("/api/queue/start")
                        .header("Authorization", "Bearer " + ownerJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueOpen").value(true))
                .andExpect(jsonPath("$.publicQueueId").isNotEmpty())
                .andReturn();

        QueueDashboardResponse dash = objectMapper.readValue(startResult.getResponse().getContentAsString(), QueueDashboardResponse.class);
        String publicQueueId = dash.getPublicQueueId();

        // 2. Owner Manually Adds Customer 1
        CustomerManualAddRequest manualReq = new CustomerManualAddRequest("Deepak Kumar", "9988776655");
        mockMvc.perform(post("/api/queue/customers/manual")
                        .header("Authorization", "Bearer " + ownerJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(manualReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCustomers").value(1))
                .andExpect(jsonPath("$.customers[0].tokenNumber").value("001"))
                .andExpect(jsonPath("$.customers[0].customerName").value("Deepak Kumar"));

        // 3. Customer 2 Previews Queue Info via Public Endpoint
        mockMvc.perform(get("/api/public/queue/" + publicQueueId + "/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value("Elite Barber & Spa"))
                .andExpect(jsonPath("$.category").value("Barber Shop"))
                .andExpect(jsonPath("$.queueOpen").value(true))
                .andExpect(jsonPath("$.totalWaiting").value(1));

        // 3b. Customer 2 Joins via QR Code (Public Endpoint)
        CustomerJoinRequest qrReq = new CustomerJoinRequest("Ananya Roy", "9123456780");
        var qrResult = mockMvc.perform(post("/api/public/queue/" + publicQueueId + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(qrReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenNumber").value("002"))
                .andExpect(jsonPath("$.category").value("Barber Shop"))
                .andExpect(jsonPath("$.peopleAhead").value(1))
                .andExpect(jsonPath("$.estimatedWaitMinutes").value(12)) // 1 person * 12 min avg
                .andReturn();

        CustomerJoinResponse qrJoin = objectMapper.readValue(qrResult.getResponse().getContentAsString(), CustomerJoinResponse.class);
        String trackingToken = qrJoin.getTrackingToken();

        // 4. Customer Tracks Token
        mockMvc.perform(get("/api/public/queue/track/" + trackingToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenNumber").value("002"))
                .andExpect(jsonPath("$.category").value("Barber Shop"))
                .andExpect(jsonPath("$.peopleAhead").value(1));

        // 5. Owner Calls Next Customer (#001) - Must be CALLED, but NOT yet SERVING
        mockMvc.perform(post("/api/queue/customers/next")
                        .header("Authorization", "Bearer " + ownerJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customers[0].status").value("CALLED"))
                .andExpect(jsonPath("$.currentlyServingToken").doesNotExist());

        // Get customer 1 ID from dashboard
        var dashRes = mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + ownerJwtToken))
                .andExpect(status().isOk())
                .andReturn();
        QueueDashboardResponse curDash = objectMapper.readValue(dashRes.getResponse().getContentAsString(), QueueDashboardResponse.class);
        Long cust1Id = curDash.getCustomers().get(0).getId();

        // 6. Owner Starts Serving Customer 1
        mockMvc.perform(patch("/api/queue/customers/" + cust1Id + "/status?status=SERVING")
                        .header("Authorization", "Bearer " + ownerJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentlyServingToken").value("001"));

        // 7. Owner Completes Customer 1 - disappears from currently serving
        mockMvc.perform(patch("/api/queue/customers/" + cust1Id + "/status?status=COMPLETED")
                        .header("Authorization", "Bearer " + ownerJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customers[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentlyServingToken").doesNotExist());

        // 8. Customer 2 Now Has 0 People Ahead
        mockMvc.perform(get("/api/public/queue/track/" + trackingToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peopleAhead").value(0))
                .andExpect(jsonPath("$.estimatedWaitMinutes").value(0));

        // 9. Customer 2 Leaves Queue
        mockMvc.perform(post("/api/public/queue/track/" + trackingToken + "/leave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LEFT"));

        // 10. Owner Closes Queue
        mockMvc.perform(post("/api/queue/close")
                        .header("Authorization", "Bearer " + ownerJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueOpen").value(false));
    }

    @Test
    @DisplayName("Subscription and Admin Configuration Flow")
    void testSubscriptionAndAdminFlow() throws Exception {
        // 1. Owner views subscription status (Active 30 days initial trial)
        mockMvc.perform(get("/api/owner/subscription")
                        .header("Authorization", "Bearer " + ownerJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.configuredUpiId").isNotEmpty());

        // 2. Owner submits UTR for Yearly renewal (₹501)
        SubscriptionPaymentSubmitRequest paymentReq = new SubscriptionPaymentSubmitRequest();
        paymentReq.setPlanType(com.queueless.entity.SubscriptionPlan.YEARLY);
        paymentReq.setPaymentReference("UPI998877665544");

        mockMvc.perform(post("/api/owner/subscription/payment")
                        .header("Authorization", "Bearer " + ownerJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingPaymentReference").value("UPI998877665544"));

        // 3. Admin verifies and updates UPI config using X-Admin-Key
        AdminSettingRequest adminSetting = new AdminSettingRequest();
        adminSetting.setUpiId("business@okaxis");
        adminSetting.setUpiName("QueueLess Digital");
        adminSetting.setMonthlyPrice(new java.math.BigDecimal("51.00"));
        adminSetting.setYearlyPrice(new java.math.BigDecimal("501.00"));

        mockMvc.perform(post("/api/admin/settings")
                        .header("X-Admin-Key", "admin-secret-queueless-2026")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminSetting)))
                .andExpect(status().isOk());

        // 4. Admin activates/extends shop subscription
        AdminShopActivateRequest activateReq = new AdminShopActivateRequest();
        activateReq.setShopId(shopId);
        activateReq.setPlanType(com.queueless.entity.SubscriptionPlan.YEARLY);
        activateReq.setCustomDays(365);

        mockMvc.perform(post("/api/admin/activate")
                        .header("X-Admin-Key", "admin-secret-queueless-2026")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(activateReq)))
                .andExpect(status().isOk());

        // 5. Owner checks subscription -> extended!
        mockMvc.perform(get("/api/owner/subscription")
                        .header("Authorization", "Bearer " + ownerJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.configuredUpiId").value("business@okaxis"));
    }
}
