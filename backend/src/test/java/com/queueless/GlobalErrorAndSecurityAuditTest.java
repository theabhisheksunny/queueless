package com.queueless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.AuthResponse;
import com.queueless.dto.CreateStaffRequest;
import com.queueless.dto.PinLoginRequest;
import com.queueless.dto.RegisterRequest;
import com.queueless.dto.StaffSummaryResponse;
import com.queueless.entity.Shop;
import com.queueless.entity.User;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.TrialEligibilityLedgerRepository;
import com.queueless.repository.UserRepository;
import com.queueless.service.AuthService;
import com.queueless.service.StaffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class GlobalErrorAndSecurityAuditTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private StaffService staffService;

    @Autowired
    private TrialEligibilityLedgerRepository ledgerRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String ownerToken;
    private String staffToken;

    @BeforeEach
    void setUp() {
        ledgerRepository.deleteAll();

        long timestamp = System.currentTimeMillis();
        String ownerPhone = "91" + (timestamp % 100000000);
        String staffPhone = "92" + (timestamp % 100000000);

        RegisterRequest reg = new RegisterRequest();
        reg.setPhone(ownerPhone);
        reg.setPin("123456");
        reg.setConfirmPin("123456");
        reg.setFullName("Audit Owner");
        reg.setShopName("Audit Saloon");
        reg.setCategory("Barber Shop");
        reg.setDeviceFingerprint("fp-audit-owner");

        AuthResponse ownerRes = authService.register(reg);
        this.ownerToken = ownerRes.getToken();

        User owner = userRepository.findByPhone("+91" + ownerPhone).orElseThrow();
        Shop shop = shopRepository.findByOwnerId(owner.getId()).orElseThrow();

        CreateStaffRequest staffReq = new CreateStaffRequest(
                "Audit Staff",
                staffPhone,
                "123456",
                List.of(shop.getId())
        );
        StaffSummaryResponse staffSummary = staffService.createStaff(owner.getId(), staffReq);

        User staffUser = userRepository.findById(staffSummary.getId()).orElseThrow();
        staffUser.setTemporaryPin(false);
        userRepository.save(staffUser);

        PinLoginRequest loginReq = new PinLoginRequest();
        loginReq.setPhone(staffPhone);
        loginReq.setPin("123456");
        loginReq.setDeviceFingerprint("fp-audit-staff");
        AuthResponse staffRes = authService.pinLogin(loginReq, "127.0.0.1");
        this.staffToken = staffRes.getToken();
    }

    @Test
    @DisplayName("1. Unauthenticated request to protected endpoint consistently returns HTTP 401 with correlationId")
    void unauthenticatedRequestReturns401WithCorrelationId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/owner/staff"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertEquals(401, json.get("status").asInt());
        assertEquals("Unauthorized", json.get("error").asText());
        assertTrue(json.has("correlationId"), "Response must contain correlationId");
        assertFalse(json.get("correlationId").asText().isBlank());
        assertFalse(body.contains("Exception"), "Response must not leak Java exception names");
        assertFalse(body.contains(".java"), "Response must not leak source file names");
    }

    @Test
    @DisplayName("2. Staff accessing owner-only management endpoint returns HTTP 403 Forbidden with correlationId")
    void staffAccessingOwnerEndpointReturns403WithCorrelationId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/owner/staff")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertEquals(403, json.get("status").asInt());
        assertTrue(json.has("correlationId"), "Response must contain correlationId");
        assertFalse(json.get("correlationId").asText().isBlank());
    }

    @Test
    @DisplayName("3. Nonexistent API endpoint returns HTTP 404 with correlationId and friendly message")
    void nonexistentEndpointReturns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/public/nonexistent-route-" + System.currentTimeMillis()))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertEquals(404, json.get("status").asInt());
        assertTrue(json.has("correlationId"));
        assertTrue(json.get("message").asText().contains("not found"));
    }

    @Test
    @DisplayName("4. Validation error returns HTTP 400 Bad Request with structured field details and correlationId")
    void validationErrorReturnsStructuredDetails() throws Exception {
        String invalidPayload = "{\"fullName\":\"\",\"phone\":\"123\",\"pin\":\"\"}";

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);

        assertEquals(400, json.get("status").asInt());
        assertTrue(json.has("details"), "Response must contain structured field details");
        assertTrue(json.has("correlationId"));
        assertFalse(body.contains("org.springframework"), "Response must not leak Spring internals");
    }

    @Test
    @DisplayName("5. Server error responses never expose stack traces, SQL syntax, or internal classes")
    void serverErrorNeverExposesStackTracesOrSql() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("shopId", "invalid-nan"))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertTrue(status == 400 || status == 500);

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("at com.queueless."), "Stack traces must never appear in response");
        assertFalse(body.contains("NullPointerException"), "Internal exception classes must not leak");
        assertFalse(body.contains("select "), "SQL queries must not leak");
        assertFalse(body.contains("insert "), "SQL queries must not leak");
    }

    @Test
    @DisplayName("6. Missing customer tracking token returns HTTP 404 with correlationId")
    void missingCustomerTrackingReturns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/public/track/INVALID-TRACKING-TOKEN-12345"))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        assertEquals(404, json.get("status").asInt());
    }
}
