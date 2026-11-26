package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.*;
import com.queueless.entity.CustomerQueueStatus;
import com.queueless.security.LogSanitizer;
import com.queueless.service.SseTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class P0SecurityRemediationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestRegistrationHelper testRegistrationHelper;

    @Autowired
    private SseTicketService sseTicketService;

    private String ownerTokenA;
    private Long shopIdA;

    private String ownerTokenB;
    private Long shopIdB;

    @BeforeEach
    void setUp() throws Exception {
        // Register Shop Owner A
        ownerTokenA = registerOwner("ownerA_" + System.currentTimeMillis() + "@test.com", "Shop Alpha");
        shopIdA = getShopIdFromToken(ownerTokenA);

        // Register Shop Owner B
        ownerTokenB = registerOwner("ownerB_" + System.currentTimeMillis() + "@test.com", "Shop Beta");
        shopIdB = getShopIdFromToken(ownerTokenB);
    }

    private String registerOwner(String email, String shopName) throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Owner " + shopName);
        req.setEmail(email);
        req.setPassword("Password123!");
        req.setPhone("98" + (10000000 + (int)(Math.random() * 89999999)));
        testRegistrationHelper.preparePin(req);
        req.setShopName(shopName);
        req.setCategory("Barber Shop");
        req.setAvgServiceDurationMinutes(15);

        var result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        AuthResponse auth = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        return auth.getToken();
    }

    private Long getShopIdFromToken(String token) throws Exception {
        var result = mockMvc.perform(get("/api/owner/settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    // =========================================================================
    // ITEM 1 TESTS: Dedicated SSE Ticket Mechanism & Token Query Parameter Removal
    // =========================================================================

    @Test
    @DisplayName("SseTicketService: Issue, atomic single-use consumption, and replay rejection")
    void testSseTicketUnitIssuanceAndReplay() {
        SseTicketService.SseTicket ticket = sseTicketService.issueTicket(100L, 200L);
        assertNotNull(ticket);
        assertNotNull(ticket.ticket());
        assertTrue(ticket.ticket().startsWith("stk_"));
        assertEquals(100L, ticket.userId());
        assertEquals(200L, ticket.shopId());

        // First consumption succeeds
        SseTicketService.SseTicket consumed = sseTicketService.consumeTicket(ticket.ticket(), 200L);
        assertEquals(ticket.ticket(), consumed.ticket());

        // Replay attempt must fail closed with AccessDeniedException
        assertThrows(AccessDeniedException.class, () ->
                sseTicketService.consumeTicket(ticket.ticket(), 200L));
    }

    @Test
    @DisplayName("SseTicketService: Expired ticket after 60 seconds is rejected")
    void testSseTicketExpiry() {
        Instant initialTime = Instant.now();
        Clock mutableClock = Clock.fixed(initialTime, ZoneId.of("UTC"));
        SseTicketService serviceWithClock = new SseTicketService(mutableClock);

        SseTicketService.SseTicket ticket = serviceWithClock.issueTicket(101L, 201L);

        // Advance clock by 61 seconds (beyond 60s TTL)
        Clock expiredClock = Clock.fixed(initialTime.plus(Duration.ofSeconds(61)), ZoneId.of("UTC"));
        serviceWithClock.setClock(expiredClock);

        assertThrows(AccessDeniedException.class, () ->
                serviceWithClock.consumeTicket(ticket.ticket(), 201L));
    }

    @Test
    @DisplayName("SseTicketService: Wrong shop mismatch is rejected")
    void testSseTicketWrongShopMismatch() {
        SseTicketService.SseTicket ticket = sseTicketService.issueTicket(100L, 200L);

        // Consuming with wrong expectedShopId must fail closed
        assertThrows(AccessDeniedException.class, () ->
                sseTicketService.consumeTicket(ticket.ticket(), 999L));
    }

    @Test
    @DisplayName("SseTicketService: Wrong owner mismatch is rejected")
    void testSseTicketWrongOwnerMismatch() {
        SseTicketService.SseTicket ticket = sseTicketService.issueTicket(100L, 200L);

        // Consuming with wrong expectedUserId must fail closed
        assertThrows(AccessDeniedException.class, () ->
                sseTicketService.consumeTicket(ticket.ticket(), 200L, 999L));
    }

    @Test
    @DisplayName("SseTicketService: Invalid or null ticket is rejected")
    void testSseTicketInvalidRejected() {
        assertThrows(AccessDeniedException.class, () ->
                sseTicketService.consumeTicket("stk_nonexistent_ticket", 200L));
        assertThrows(AccessDeniedException.class, () ->
                sseTicketService.consumeTicket("", 200L));
        assertThrows(AccessDeniedException.class, () ->
                sseTicketService.consumeTicket(null, 200L));
    }

    @Test
    @DisplayName("HTTP Flow: Create SSE Ticket via POST and connect to stream; verify replay rejected")
    void testSseTicketHttpLifecycle() throws Exception {
        // 1. Authenticated POST to obtain single-use ticket
        var ticketResult = mockMvc.perform(post("/api/queue/sse-ticket")
                        .header("Authorization", "Bearer " + ownerTokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isNotEmpty())
                .andExpect(jsonPath("$.expiresInSeconds").value(60))
                .andReturn();

        SseTicketResponse ticketResp = objectMapper.readValue(ticketResult.getResponse().getContentAsString(), SseTicketResponse.class);
        String ticket = ticketResp.getTicket();
        assertTrue(ticket.startsWith("stk_"));

        // 2. Connect to SSE stream using ticket and tenant shopId (no Authorization header required)
        mockMvc.perform(get("/api/queue/stream/owner")
                        .param("ticket", ticket)
                        .param("shopId", String.valueOf(shopIdA)))
                .andExpect(status().isOk());

        // 3. Replay with the same ticket is rejected with 403 Forbidden
        mockMvc.perform(get("/api/queue/stream/owner")
                        .param("ticket", ticket)
                        .param("shopId", String.valueOf(shopIdA)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security Gate: Long-lived JWT passed via query parameter 'token' is strictly rejected")
    void testJwtQueryParameterRejected() throws Exception {
        // Passing long-lived JWT in query parameter without Authorization header MUST return 403 Forbidden
        mockMvc.perform(get("/api/queue/stream/owner")
                        .param("token", ownerTokenA))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // ITEM 2 TESTS: Cross-Shop Customer IDOR Remediation & Tenant Isolation
    // =========================================================================

    @Test
    @DisplayName("IDOR Prevention: Shop A cannot modify, edit, or remove Shop B's customer")
    void testCrossShopCustomerIdorPrevention() throws Exception {
        // Start queues for both shops
        mockMvc.perform(post("/api/queue/start").header("Authorization", "Bearer " + ownerTokenA)).andExpect(status().isOk());
        mockMvc.perform(post("/api/queue/start").header("Authorization", "Bearer " + ownerTokenB)).andExpect(status().isOk());

        // Shop A adds Customer A
        var addAResult = mockMvc.perform(post("/api/queue/customers/manual")
                        .header("Authorization", "Bearer " + ownerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerManualAddRequest("Customer A", "9811111111"))))
                .andExpect(status().isOk())
                .andReturn();
        QueueDashboardResponse dashA = objectMapper.readValue(addAResult.getResponse().getContentAsString(), QueueDashboardResponse.class);
        Long customerIdA = dashA.getCustomers().get(0).getId();

        // Shop B adds Customer B
        var addBResult = mockMvc.perform(post("/api/queue/customers/manual")
                        .header("Authorization", "Bearer " + ownerTokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerManualAddRequest("Customer B", "9822222222"))))
                .andExpect(status().isOk())
                .andReturn();
        QueueDashboardResponse dashB = objectMapper.readValue(addBResult.getResponse().getContentAsString(), QueueDashboardResponse.class);
        Long customerIdB = dashB.getCustomers().get(0).getId();

        // 1. Legitimacy check: Shop A can modify its own Customer A
        mockMvc.perform(patch("/api/queue/customers/" + customerIdA + "/status")
                        .header("Authorization", "Bearer " + ownerTokenA)
                        .param("status", "SERVING"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/queue/customers/" + customerIdA)
                        .header("Authorization", "Bearer " + ownerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerEditRequest("Customer A Updated", "9811111111"))))
                .andExpect(status().isOk());

        // 2. IDOR Attack 1: Shop A attempts to change status of Customer B -> MUST BE 403 FORBIDDEN
        mockMvc.perform(patch("/api/queue/customers/" + customerIdB + "/status")
                        .header("Authorization", "Bearer " + ownerTokenA)
                        .param("status", "COMPLETED"))
                .andExpect(status().isForbidden());

        // 3. IDOR Attack 2: Shop A attempts to edit Customer B -> MUST BE 403 FORBIDDEN
        mockMvc.perform(put("/api/queue/customers/" + customerIdB)
                        .header("Authorization", "Bearer " + ownerTokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerEditRequest("Hacked Name", "9999999999"))))
                .andExpect(status().isForbidden());

        // 4. IDOR Attack 3: Shop A attempts to remove Customer B -> MUST BE 403 FORBIDDEN
        mockMvc.perform(delete("/api/queue/customers/" + customerIdB)
                        .header("Authorization", "Bearer " + ownerTokenA))
                .andExpect(status().isForbidden());

        // 5. IDOR Attack 4: Parameter Tampering — Shop A supplies shopId=ShopB in query param -> 403 FORBIDDEN
        mockMvc.perform(patch("/api/queue/customers/" + customerIdB + "/status")
                        .header("Authorization", "Bearer " + ownerTokenA)
                        .param("shopId", String.valueOf(shopIdB))
                        .param("status", "COMPLETED"))
                .andExpect(status().isForbidden());

        // 6. Verify Customer B in Shop B remained completely untouched (still WAITING, original name)
        var dashBCheck = mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + ownerTokenB))
                .andExpect(status().isOk())
                .andReturn();
        QueueDashboardResponse dashBFinal = objectMapper.readValue(dashBCheck.getResponse().getContentAsString(), QueueDashboardResponse.class);
        assertEquals(1, dashBFinal.getCustomers().size());
        assertEquals("Customer B", dashBFinal.getCustomers().get(0).getCustomerName());
        assertEquals(CustomerQueueStatus.WAITING, dashBFinal.getCustomers().get(0).getStatus());
    }

    @Test
    @DisplayName("Tenant Isolation: Shop A owner cannot read Shop B dashboard via parameter tampering")
    void testCrossShopDashboardReadIsolation() throws Exception {
        // Shop A attempts to read Shop B's queue dashboard by passing shopId=shopIdB
        mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + ownerTokenA)
                        .param("shopId", String.valueOf(shopIdB)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // ITEM 3 TESTS: Privacy, Log Sanitization & Response Headers
    // =========================================================================

    @Test
    @DisplayName("LogSanitizer: Sensitive tokens, tickets, and keys are redacted from URLs and logs")
    void testLogSanitizerRedaction() {
        // Redact tickets in SSE URLs
        String sseUrl = "https://queueless.theabhisheksunny.workers.dev/api/queue/stream/owner?ticket=stk_abcdef123456&shopId=10";
        String sanitizedSse = LogSanitizer.sanitizeUrl(sseUrl);
        assertEquals("https://queueless.theabhisheksunny.workers.dev/api/queue/stream/owner?ticket=[REDACTED]&shopId=10", sanitizedSse);

        // Redact tokens and passwords
        String complexUrl = "https://example.com/api?token=eyJhbGciOiJIUzI1NiJ9&password=SecretPass123&adminKey=adm_999";
        String sanitizedComplex = LogSanitizer.sanitizeUrl(complexUrl);
        assertEquals("https://example.com/api?token=[REDACTED]&password=[REDACTED]&adminKey=[REDACTED]", sanitizedComplex);

        // Redact Bearer headers in log messages
        String logMessage = "Request failed for Bearer eyJhbGciOiJIUzI1NiJ9.user.sig with status 401";
        String sanitizedLog = LogSanitizer.sanitizeMessage(logMessage);
        assertEquals("Request failed for Bearer [REDACTED] with status 401", sanitizedLog);
    }

    @Test
    @DisplayName("SseTicketService: Scheduled cleanup prunes expired tickets from memory")
    void testSseTicketCleanupExpired() {
        Instant initialTime = Instant.now();
        Clock mutableClock = Clock.fixed(initialTime, ZoneId.of("UTC"));
        SseTicketService service = new SseTicketService(mutableClock);

        service.issueTicket(1L, 10L);
        service.issueTicket(2L, 20L);
        assertEquals(2, service.getActiveTicketCount());

        // Fast forward 61 seconds (past 60s TTL)
        service.setClock(Clock.fixed(initialTime.plus(Duration.ofSeconds(61)), ZoneId.of("UTC")));

        int purged = service.cleanupExpiredTickets();
        assertEquals(2, purged);
        assertEquals(0, service.getActiveTicketCount());
    }

    @Test
    @DisplayName("Response Headers: SSE ticket issuance enforces Cache-Control: no-store and Referrer-Policy: no-referrer")
    void testSseTicketHeadersAndReferrerPolicy() throws Exception {
        mockMvc.perform(post("/api/queue/sse-ticket")
                        .header("Authorization", "Bearer " + ownerTokenA))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    @DisplayName("Permanent QR Architecture: Historical printed QR code seamlessly resolves to newly opened session")
    void testPermanentMerchantQrUrlResolutionAcrossSessions() throws Exception {
        // 1. Start initial queue session (Day 1)
        var start1 = mockMvc.perform(post("/api/queue/start").header("Authorization", "Bearer " + ownerTokenA))
                .andExpect(status().isOk())
                .andReturn();
        QueueDashboardResponse dash1 = objectMapper.readValue(start1.getResponse().getContentAsString(), QueueDashboardResponse.class);
        String publicQueueIdSession1 = dash1.getPublicQueueId();
        assertNotNull(publicQueueIdSession1);

        // 2. Customer joins session 1
        var join1 = mockMvc.perform(post("/api/public/queue/" + publicQueueIdSession1 + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerJoinRequest("Customer Day 1", "9812345678"))))
                .andExpect(status().isOk())
                .andReturn();
        CustomerJoinResponse joinResp1 = objectMapper.readValue(join1.getResponse().getContentAsString(), CustomerJoinResponse.class);
        assertEquals("001", joinResp1.getTokenNumber());

        // 3. Shop closes session 1
        mockMvc.perform(post("/api/queue/close").header("Authorization", "Bearer " + ownerTokenA))
                .andExpect(status().isOk());

        // 4. Shop starts session 2 (Day 2)
        var start2 = mockMvc.perform(post("/api/queue/start").header("Authorization", "Bearer " + ownerTokenA))
                .andExpect(status().isOk())
                .andReturn();
        QueueDashboardResponse dash2 = objectMapper.readValue(start2.getResponse().getContentAsString(), QueueDashboardResponse.class);
        String publicQueueIdSession2 = dash2.getPublicQueueId();
        assertNotNull(publicQueueIdSession2);
        assertNotEquals(publicQueueIdSession1, publicQueueIdSession2);

        // 5. Customer scans printed Day 1 QR code poster (publicQueueIdSession1) on Day 2:
        // System must resolve to the active open session (Day 2) and succeed
        var joinDay2ViaOldQr = mockMvc.perform(post("/api/public/queue/" + publicQueueIdSession1 + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerJoinRequest("Customer Day 2", "9887654321"))))
                .andExpect(status().isOk())
                .andReturn();
        CustomerJoinResponse joinResp2 = objectMapper.readValue(joinDay2ViaOldQr.getResponse().getContentAsString(), CustomerJoinResponse.class);
        assertEquals("001", joinResp2.getTokenNumber());

        // 6. Preview info using old QR code also returns queueOpen = true
        mockMvc.perform(get("/api/public/queue/" + publicQueueIdSession1 + "/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueOpen").value(true));

        // 7. Close shop queue on Day 2
        mockMvc.perform(post("/api/queue/close").header("Authorization", "Bearer " + ownerTokenA))
                .andExpect(status().isOk());

        // 8. With no open session, joining via old or new QR code fails with 409 Conflict
        mockMvc.perform(post("/api/public/queue/" + publicQueueIdSession1 + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerJoinRequest("Customer Late", "9811122233"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Canonical Permanent Merchant URL: Previews info and joins queue directly via opaque public merchant code")
    void testCanonicalPermanentMerchantUrl() throws Exception {
        // Start queue for Shop A
        mockMvc.perform(post("/api/queue/start").header("Authorization", "Bearer " + ownerTokenA))
                .andExpect(status().isOk());

        // Get dashboard to inspect opaque public merchant code
        var dashResult = mockMvc.perform(get("/api/queue/dashboard").header("Authorization", "Bearer " + ownerTokenA))
                .andExpect(status().isOk())
                .andReturn();
        QueueDashboardResponse dash = objectMapper.readValue(dashResult.getResponse().getContentAsString(), QueueDashboardResponse.class);
        String publicMerchantCode = dash.getPublicMerchantCode();
        assertNotNull(publicMerchantCode, "publicMerchantCode must be populated");
        assertTrue(publicMerchantCode.startsWith("pm_"), "publicMerchantCode must start with pm_");
        assertEquals(19, publicMerchantCode.length(), "publicMerchantCode must be 19 chars (pm_ + 16 chars)");
        assertEquals("/join/shop/" + publicMerchantCode, dash.getCanonicalJoinUrl(), "canonicalJoinUrl must use opaque code");

        // Preview canonical info using opaque public merchant code
        mockMvc.perform(get("/api/public/queue/shop/" + publicMerchantCode + "/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value("Shop Alpha"))
                .andExpect(jsonPath("$.queueOpen").value(true));

        // Join queue via canonical permanent URL
        var joinResult = mockMvc.perform(post("/api/public/queue/shop/" + publicMerchantCode + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerJoinRequest("Canonical Customer", "9812345678"))))
                .andExpect(status().isOk())
                .andReturn();

        CustomerJoinResponse joinResp = objectMapper.readValue(joinResult.getResponse().getContentAsString(), CustomerJoinResponse.class);
        assertEquals("001", joinResp.getTokenNumber());
        assertNotNull(joinResp.getTrackingToken());

        // Join queue via short URL format /api/public/queue/{publicMerchantCode}/join
        var joinShortResult = mockMvc.perform(post("/api/public/queue/" + publicMerchantCode + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerJoinRequest("Short URL Customer", "9899998888"))))
                .andExpect(status().isOk())
                .andReturn();
        CustomerJoinResponse joinShortResp = objectMapper.readValue(joinShortResult.getResponse().getContentAsString(), CustomerJoinResponse.class);
        assertEquals("002", joinShortResp.getTokenNumber());
    }

    @Test
    @DisplayName("Anti-Enumeration: Sequential shop ID probing fails closed without confirming shop existence")
    void testSequentialShopIdProbingFailsClosed() throws Exception {
        // Start queue for Shop A
        mockMvc.perform(post("/api/queue/start").header("Authorization", "Bearer " + ownerTokenA))
                .andExpect(status().isOk());

        // Probing /api/public/queue/shop/{shopIdA}/info using internal numeric shopId must FAIL CLOSED
        var infoResult = mockMvc.perform(get("/api/public/queue/shop/" + shopIdA + "/info"))
                .andExpect(status().isBadRequest())
                .andReturn();
        var infoNode = objectMapper.readTree(infoResult.getResponse().getContentAsString());
        assertEquals("Shop not found", infoNode.get("message").asText());
        assertFalse(infoNode.get("message").asText().contains(String.valueOf(shopIdA)), "Message must not reflect internal database ID");

        // Probing /api/public/queue/shop/{shopIdA}/join using internal numeric shopId must FAIL CLOSED
        var joinResult = mockMvc.perform(post("/api/public/queue/shop/" + shopIdA + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerJoinRequest("Probe", "9812345678"))))
                .andExpect(status().isBadRequest())
                .andReturn();
        var joinNode = objectMapper.readTree(joinResult.getResponse().getContentAsString());
        assertEquals("Shop not found", joinNode.get("message").asText());
        assertFalse(joinNode.get("message").asText().contains(String.valueOf(shopIdA)), "Message must not reflect internal database ID");
    }

    @Test
    @DisplayName("Anti-Enumeration: Malformed and invalid opaque identifiers fail closed")
    void testMalformedOpaqueIdentifiersFailClosed() throws Exception {
        String[] malformedCodes = {
                "pm_short",
                "pm_1234567890abcdef_toolong",
                "sh_1234567890abcdef",
                "pm_invalid_chars",
                "nonexistent_code_123"
        };

        for (String badCode : malformedCodes) {
            mockMvc.perform(get("/api/public/queue/shop/" + badCode + "/info"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Shop not found"));

            mockMvc.perform(post("/api/public/queue/shop/" + badCode + "/join")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new CustomerJoinRequest("Probe", "9812345678"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Shop not found"));
        }
    }

    @Test
    @DisplayName("Tenant Isolation: Queue tokens and customers remain isolated between distinct shops")
    void testTenantIsolationBetweenShops() throws Exception {
        // Start queue for Shop A and Shop B
        mockMvc.perform(post("/api/queue/start").header("Authorization", "Bearer " + ownerTokenA)).andExpect(status().isOk());
        mockMvc.perform(post("/api/queue/start").header("Authorization", "Bearer " + ownerTokenB)).andExpect(status().isOk());

        // Get Shop A public merchant code
        var dashA = objectMapper.readValue(
                mockMvc.perform(get("/api/queue/dashboard").header("Authorization", "Bearer " + ownerTokenA))
                        .andReturn().getResponse().getContentAsString(),
                QueueDashboardResponse.class
        );
        String codeA = dashA.getPublicMerchantCode();

        // Customer joins Shop A
        mockMvc.perform(post("/api/public/queue/shop/" + codeA + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerJoinRequest("Alice in Shop A", "9811111111"))))
                .andExpect(status().isOk());

        // Verify Shop A has 1 customer
        var updatedA = objectMapper.readValue(
                mockMvc.perform(get("/api/queue/dashboard").header("Authorization", "Bearer " + ownerTokenA))
                        .andReturn().getResponse().getContentAsString(),
                QueueDashboardResponse.class
        );
        assertEquals(1, updatedA.getWaitingCustomers());

        // Verify Shop B remains at 0 customers (strict tenant isolation)
        var updatedB = objectMapper.readValue(
                mockMvc.perform(get("/api/queue/dashboard").header("Authorization", "Bearer " + ownerTokenB))
                        .andReturn().getResponse().getContentAsString(),
                QueueDashboardResponse.class
        );
        assertEquals(0, updatedB.getWaitingCustomers());
    }

    // =========================================================================
    // ITEM 3/4 TESTS: Internal Database Identifier Security & Anti-Enumeration
    // =========================================================================

    @Test
    @DisplayName("URI Security: Public routes and responses never disclose internal database primary keys")
    void testPublicRoutesDoNotLeakInternalDatabaseIdentifiers() throws Exception {
        // Start queue
        var startResp = mockMvc.perform(post("/api/queue/start").header("Authorization", "Bearer " + ownerTokenA))
                .andExpect(status().isOk())
                .andReturn();
        QueueDashboardResponse dash = objectMapper.readValue(startResp.getResponse().getContentAsString(), QueueDashboardResponse.class);
        String publicQueueId = dash.getPublicQueueId();

        // 1. Inspect GET /api/public/queue/{publicQueueId}/info
        var infoResult = mockMvc.perform(get("/api/public/queue/" + publicQueueId + "/info"))
                .andExpect(status().isOk())
                .andReturn();
        var infoNode = objectMapper.readTree(infoResult.getResponse().getContentAsString());
        assertFalse(infoNode.has("id"), "Public info response must not expose 'id'");
        assertFalse(infoNode.has("shopId"), "Public info response must not expose 'shopId'");
        assertFalse(infoNode.has("ownerId"), "Public info response must not expose 'ownerId'");
        assertFalse(infoNode.has("sessionId"), "Public info response must not expose 'sessionId'");
        assertFalse(infoNode.has("userId"), "Public info response must not expose 'userId'");

        // 2. Inspect POST /api/public/queue/{publicQueueId}/join
        var joinResult = mockMvc.perform(post("/api/public/queue/" + publicQueueId + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerJoinRequest("Audit Customer", "9812345678"))))
                .andExpect(status().isOk())
                .andReturn();
        var joinNode = objectMapper.readTree(joinResult.getResponse().getContentAsString());
        assertFalse(joinNode.has("id"), "Public join response must not expose 'id'");
        assertFalse(joinNode.has("customerId"), "Public join response must not expose 'customerId'");
        assertFalse(joinNode.has("sessionId"), "Public join response must not expose 'sessionId'");
        assertFalse(joinNode.has("shopId"), "Public join response must not expose 'shopId'");
        assertNotNull(joinNode.get("trackingToken").asText());

        // 3. Inspect GET /api/public/queue/track/{trackingToken}
        String trackingToken = joinNode.get("trackingToken").asText();
        var trackResult = mockMvc.perform(get("/api/public/queue/track/" + trackingToken))
                .andExpect(status().isOk())
                .andReturn();
        var trackNode = objectMapper.readTree(trackResult.getResponse().getContentAsString());
        assertFalse(trackNode.has("id"), "Tracking response must not expose internal customer 'id'");
        assertFalse(trackNode.has("shopId"), "Tracking response must not expose internal 'shopId'");
        assertFalse(trackNode.has("sessionId"), "Tracking response must not expose internal 'sessionId'");
        assertFalse(trackNode.has("ownerId"), "Tracking response must not expose internal 'ownerId'");
    }

    @Test
    @DisplayName("Anti-Enumeration: Probing non-existent shop IDs fails closed with sanitized error")
    void testShopIdEnumerationFailsClosedWithoutLeakingDatabaseDetails() throws Exception {
        Long nonExistentShopId = 999999L;

        // Probing /api/public/queue/shop/{nonExistentShopId}/info
        var infoResult = mockMvc.perform(get("/api/public/queue/shop/" + nonExistentShopId + "/info"))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = infoResult.getResponse().getContentAsString();
        assertTrue(body.contains("Shop not found"), "Error body must contain generic sanitized message");
        assertFalse(body.contains("999999"), "Error body must NOT reflect back the probed ID");
        assertFalse(body.contains("with ID:"), "Error body must NOT confirm sequential ID query");
        assertFalse(body.contains("SQLException") || body.contains("org.hibernate"), "No database stack trace leaks");

        // Probing /api/public/queue/shop/{nonExistentShopId}/join
        var joinResult = mockMvc.perform(post("/api/public/queue/shop/" + nonExistentShopId + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerJoinRequest("Probe Customer", "9812345678"))))
                .andExpect(status().isBadRequest())
                .andReturn();

        String joinBody = joinResult.getResponse().getContentAsString();
        assertFalse(joinBody.contains("999999"), "Join error body must NOT reflect back the probed ID");
        assertFalse(joinBody.contains("SQLException") || joinBody.contains("org.hibernate"), "No database stack trace leaks");
    }

    @Test
    @DisplayName("Anti-Enumeration: Probing non-existent publicQueueId fails closed with sanitized error")
    void testPublicQueueIdEnumerationFailsClosedWithoutLeakingDatabaseDetails() throws Exception {
        String nonExistentQueue = "nonexistent_queue_probe_abc123";

        var result = mockMvc.perform(get("/api/public/queue/" + nonExistentQueue + "/info"))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("Invalid Queue QR code or link."), "Must return sanitized error message");
        assertFalse(body.contains("SQLException") || body.contains("org.hibernate"), "No database stack trace leaks");
    }
}
