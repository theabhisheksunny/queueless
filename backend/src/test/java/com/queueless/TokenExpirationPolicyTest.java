package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.CustomerJoinRequest;
import com.queueless.dto.CustomerJoinResponse;
import com.queueless.dto.QueueDashboardResponse;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.CustomerQueueStatus;
import com.queueless.entity.QueueCustomer;
import com.queueless.exception.TokenExpiredException;
import com.queueless.model.CustomerPositionInfo;
import com.queueless.repository.QueueCustomerRepository;
import com.queueless.service.AuthService;
import com.queueless.service.QueueService;
import com.queueless.service.SseEmitterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class TokenExpirationPolicyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QueueService queueService;

    @Autowired
    private QueueCustomerRepository customerRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private SseEmitterService sseEmitterService;

    @Autowired
    private TestRegistrationHelper testRegistrationHelper;

    private Long testShopId;
    private String publicQueueId;
    private Clock baseClock;

    @BeforeEach
    void setUp() {
        // Fix base clock for controllable testing
        baseClock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneId.of("UTC"));
        queueService.setClock(baseClock);

        // Register a test shop
        RegisterRequest registerReq = new RegisterRequest();
        registerReq.setFullName("Master Barber");
        registerReq.setEmail("barber_" + System.currentTimeMillis() + "@queueless.com");
        registerReq.setPassword("password123");
        registerReq.setPhone("98" + (10000000 + (int)(Math.random() * 89999999)));
        testRegistrationHelper.prepareOtp(registerReq);
        registerReq.setShopName("Precision Hair Studio");
        registerReq.setCategory("Salon");
        registerReq.setAvgServiceDurationMinutes(15);

        var authRes = authService.register(registerReq);
        testShopId = authRes.getShopId();

        QueueDashboardResponse dash = queueService.startQueue(testShopId);
        publicQueueId = dash.getPublicQueueId();
    }

    @AfterEach
    void tearDown() {
        // Reset clock back to system default
        queueService.setClock(Clock.systemDefaultZone());
    }

    @Test
    @DisplayName("Comprehensive 11-Point Completed Token 15-Minute Expiration Policy Test")
    void testCompletedTokenExpirationLifecycle() throws Exception {
        // ---------------------------------------------------------------------
        // Step 1: Customer joins via QR code
        // ---------------------------------------------------------------------
        CustomerJoinRequest joinReq = new CustomerJoinRequest("Rohan Verma", "9876500001");
        CustomerJoinResponse joinRes = queueService.customerJoinQueue(publicQueueId, joinReq);
        String trackingToken = joinRes.getTrackingToken();
        assertEquals("001", joinRes.getTokenNumber());

        QueueDashboardResponse dash = queueService.getDashboard(testShopId);
        Long customerId = dash.getCustomers().get(0).getId();

        // Step 1 & 2: Call Next, Start Serving, Complete Service
        queueService.updateCustomerStatus(testShopId, customerId, CustomerQueueStatus.CALLED);
        queueService.updateCustomerStatus(testShopId, customerId, CustomerQueueStatus.SERVING);
        queueService.updateCustomerStatus(testShopId, customerId, CustomerQueueStatus.COMPLETED);

        // Verify completion timestamp is persisted in DB and in memory
        QueueCustomer completedCustomer = customerRepository.findById(customerId).orElseThrow();
        assertNotNull(completedCustomer.getCompletedAt(), "Completion timestamp must be saved");
        LocalDateTime originalCompletedAt = completedCustomer.getCompletedAt();

        // Verify repeated completion call does not reset the expiration timestamp
        queueService.updateCustomerStatus(testShopId, customerId, CustomerQueueStatus.COMPLETED);
        QueueCustomer afterRepeated = customerRepository.findById(customerId).orElseThrow();
        assertEquals(originalCompletedAt, afterRepeated.getCompletedAt(),
                "Repeated completion must not reset completedAt timestamp");

        // ---------------------------------------------------------------------
        // Step 3: Tracking URL works before 15 minutes (e.g. at T + 5 mins)
        // ---------------------------------------------------------------------
        queueService.setClock(Clock.offset(baseClock, Duration.ofMinutes(5)));

        CustomerPositionInfo positionBeforeExpiry = queueService.getCustomerTrackingInfo(trackingToken);
        assertNotNull(positionBeforeExpiry);
        assertEquals("001", positionBeforeExpiry.getTokenNumber());
        assertEquals("Precision Hair Studio", positionBeforeExpiry.getShopName());
        assertEquals(CustomerQueueStatus.COMPLETED, positionBeforeExpiry.getStatus());
        assertNotNull(positionBeforeExpiry.getCompletedAt());
        assertNotNull(positionBeforeExpiry.getExpiresAt());

        // Verify MockMvc GET /api/public/queue/track/{trackingToken} returns 200 OK before 15 mins
        mockMvc.perform(get("/api/public/queue/track/" + trackingToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenNumber").value("001"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // ---------------------------------------------------------------------
        // Step 4 & 5: Tracking URL expires after 15 minutes (e.g. at T + 16 mins)
        // Returns HTTP 410 Gone
        // ---------------------------------------------------------------------
        queueService.setClock(Clock.offset(baseClock, Duration.ofMinutes(16)));

        // Service level throws TokenExpiredException
        assertThrows(TokenExpiredException.class, () -> {
            queueService.getCustomerTrackingInfo(trackingToken);
        }, "getCustomerTrackingInfo must throw TokenExpiredException after 15 minutes");

        // REST endpoint returns HTTP 410 Gone
        mockMvc.perform(get("/api/public/queue/track/" + trackingToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.error").value("Your token has expired. Your service was already completed."));

        // Leave queue API also returns 410 Gone
        mockMvc.perform(post("/api/public/queue/track/" + trackingToken + "/leave"))
                .andExpect(status().isGone());

        // ---------------------------------------------------------------------
        // Step 6: SSE tracking connection cannot continue beyond expiration
        // ---------------------------------------------------------------------
        mockMvc.perform(get("/api/public/queue/stream/customer/" + trackingToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410));

        // ---------------------------------------------------------------------
        // Step 7: Owner can still view the completed customer in history
        // ---------------------------------------------------------------------
        QueueDashboardResponse ownerHistoryDash = queueService.getDashboard(testShopId);
        assertNotNull(ownerHistoryDash);
        assertEquals(1, ownerHistoryDash.getTotalCustomers(), "Owner must still see completed customer in history");
        var historicalCustomer = ownerHistoryDash.getCustomers().get(0);
        assertEquals("001", historicalCustomer.getTokenNumber());
        assertEquals("Rohan Verma", historicalCustomer.getCustomerName());
        assertEquals(CustomerQueueStatus.COMPLETED, historicalCustomer.getStatus());
        assertEquals(originalCompletedAt, historicalCustomer.getCompletedAt());

        // ---------------------------------------------------------------------
        // Step 8: Completed customer cannot rejoin using the old token
        // ---------------------------------------------------------------------
        // Reset clock back to T + 5 mins to verify rejoin is forbidden even before expiration
        queueService.setClock(Clock.offset(baseClock, Duration.ofMinutes(5)));
        Exception rejoinEx = assertThrows(IllegalStateException.class, () -> {
            queueService.customerRejoinQueue(trackingToken);
        });
        assertTrue(rejoinEx.getMessage().contains("Completed tokens cannot rejoin"),
                "Rejoining a completed token must be rejected");

        // Rejoin at T + 16 mins must also fail with 410 Gone
        queueService.setClock(Clock.offset(baseClock, Duration.ofMinutes(16)));
        assertThrows(TokenExpiredException.class, () -> {
            queueService.customerRejoinQueue(trackingToken);
        });

        // ---------------------------------------------------------------------
        // Step 9 & 10: The same customer can join again through QR and receives a new token
        // New token is appended at the end of the queue
        // ---------------------------------------------------------------------
        CustomerJoinRequest repeatJoinReq = new CustomerJoinRequest("Rohan Verma", "9876500001");
        CustomerJoinResponse repeatJoinRes = queueService.customerJoinQueue(publicQueueId, repeatJoinReq);

        assertNotNull(repeatJoinRes.getTrackingToken());
        assertNotEquals(trackingToken, repeatJoinRes.getTrackingToken(), "New join must have a unique tracking token");
        assertEquals("002", repeatJoinRes.getTokenNumber(), "New token must be 002");

        // ---------------------------------------------------------------------
        // Step 11: Existing completed token remains unchanged
        // ---------------------------------------------------------------------
        QueueCustomer originalCustomerInDb = customerRepository.findByTrackingToken(trackingToken).orElseThrow();
        assertEquals("001", originalCustomerInDb.getTokenNumber());
        assertEquals(CustomerQueueStatus.COMPLETED, originalCustomerInDb.getStatus());
        assertEquals(originalCompletedAt, originalCustomerInDb.getCompletedAt());

        QueueDashboardResponse updatedOwnerDash = queueService.getDashboard(testShopId);
        assertEquals(2, updatedOwnerDash.getTotalCustomers());
        assertEquals(1, updatedOwnerDash.getWaitingCustomers());
        assertEquals("001", updatedOwnerDash.getCustomers().get(0).getTokenNumber());
        assertEquals(CustomerQueueStatus.COMPLETED, updatedOwnerDash.getCustomers().get(0).getStatus());
        assertEquals("002", updatedOwnerDash.getCustomers().get(1).getTokenNumber());
        assertEquals(CustomerQueueStatus.WAITING, updatedOwnerDash.getCustomers().get(1).getStatus());
    }
}
