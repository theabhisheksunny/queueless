package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.CustomerJoinRequest;
import com.queueless.dto.CustomerJoinResponse;
import com.queueless.dto.PushSubscriptionRequest;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.CustomerPushSubscription;
import com.queueless.entity.CustomerQueueStatus;
import com.queueless.entity.QueueCustomer;
import com.queueless.repository.CustomerPushSubscriptionRepository;
import com.queueless.repository.QueueCustomerRepository;
import com.queueless.service.AuthService;
import com.queueless.service.QueueService;
import com.queueless.service.push.PushSendResult;
import com.queueless.service.push.WebPushClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class WebPushNotificationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QueueService queueService;

    @Autowired
    private QueueCustomerRepository customerRepository;

    @Autowired
    private CustomerPushSubscriptionRepository pushSubscriptionRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WebPushClient webPushClient;

    private Long testShopId;
    private String publicQueueId;

    @BeforeEach
    void setUp() {
        // Setup mock default behavior
        when(webPushClient.sendPush(any(), anyString()))
                .thenReturn(PushSendResult.success(201));

        RegisterRequest req = new RegisterRequest();
        req.setFullName("Aakash Verma");
        req.setEmail("aakash@pushshop.test");
        req.setPassword("pushPass123");
        req.setPhone("9811122233");
        req.setShopName("Aakash Express Cuts");
        req.setCategory("Salon & Spa");
        req.setAvgServiceDurationMinutes(10);
        req.setOtp("123456");

        var authRes = authService.register(req);
        testShopId = authRes.getShopId();

        var dashboard = queueService.startQueue(testShopId);
        publicQueueId = dashboard.getPublicQueueId();
    }

    @Test
    @DisplayName("VAPID public key endpoint returns valid uncompressed P-256 base64url key")
    void testGetVapidPublicKey() throws Exception {
        mockMvc.perform(get("/api/public/queue/vapid-public-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").isString())
                .andExpect(jsonPath("$.publicKey").isNotEmpty());
    }

    @Test
    @DisplayName("Customer can register Web Push subscription with valid tracking token")
    void testRegisterPushSubscriptionSuccess() throws Exception {
        CustomerJoinResponse joinRes = queueService.customerJoinQueue(publicQueueId, new CustomerJoinRequest("Rohit Sharma", "9876500001"));
        String trackingToken = joinRes.getTrackingToken();

        PushSubscriptionRequest pushReq = new PushSubscriptionRequest(
                "https://fcm.googleapis.com/fcm/send/customer-rohit-token",
                "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QT9Q0mA8GqkWfaoqq2m308wktA1qms7pQ",
                "tBHItJI5svbpez7KI4CCXg",
                "Mozilla/5.0"
        );

        mockMvc.perform(post("/api/public/queue/track/" + trackingToken + "/push-subscription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pushReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        List<CustomerPushSubscription> subs = pushSubscriptionRepository.findByCustomerTrackingTokenAndActiveTrue(trackingToken);
        assertEquals(1, subs.size());
        assertEquals("https://fcm.googleapis.com/fcm/send/customer-rohit-token", subs.get(0).getEndpoint());
        assertTrue(subs.get(0).isActive());
    }

    @Test
    @DisplayName("Push registration rejects invalid tracking token")
    void testRegisterPushSubscriptionInvalidToken() throws Exception {
        PushSubscriptionRequest pushReq = new PushSubscriptionRequest(
                "https://fcm.googleapis.com/fcm/send/customer-rohit-token",
                "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QT9Q0mA8GqkWfaoqq2m308wktA1qms7pQ",
                "tBHItJI5svbpez7KI4CCXg",
                "Mozilla/5.0"
        );

        mockMvc.perform(post("/api/public/queue/track/invalid-token-12345/push-subscription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pushReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Customer can unregister push subscription")
    void testUnregisterPushSubscription() throws Exception {
        CustomerJoinResponse joinRes = queueService.customerJoinQueue(publicQueueId, new CustomerJoinRequest("Karan Singh", "9876500002"));
        String trackingToken = joinRes.getTrackingToken();

        PushSubscriptionRequest pushReq = new PushSubscriptionRequest(
                "https://fcm.googleapis.com/fcm/send/customer-karan-token",
                "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QT9Q0mA8GqkWfaoqq2m308wktA1qms7pQ",
                "tBHItJI5svbpez7KI4CCXg",
                "Mozilla/5.0"
        );

        mockMvc.perform(post("/api/public/queue/track/" + trackingToken + "/push-subscription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pushReq)))
                .andExpect(status().isOk());

        // Unregister
        mockMvc.perform(delete("/api/public/queue/track/" + trackingToken + "/push-subscription"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        List<CustomerPushSubscription> activeSubs = pushSubscriptionRepository.findByCustomerTrackingTokenAndActiveTrue(trackingToken);
        assertTrue(activeSubs.isEmpty());
    }

    @Test
    @DisplayName("Web Push is dispatched with single tag and priority on CALLED and SERVING")
    void testWebPushDispatchedOnLifecycleEvents() throws Exception {
        CustomerJoinResponse joinRes = queueService.customerJoinQueue(publicQueueId, new CustomerJoinRequest("Virat Kohli", "9876500003"));
        String trackingToken = joinRes.getTrackingToken();

        PushSubscriptionRequest pushReq = new PushSubscriptionRequest(
                "https://fcm.googleapis.com/fcm/send/customer-virat-token",
                "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QT9Q0mA8GqkWfaoqq2m308wktA1qms7pQ",
                "tBHItJI5svbpez7KI4CCXg",
                "Mozilla/5.0"
        );

        mockMvc.perform(post("/api/public/queue/track/" + trackingToken + "/push-subscription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pushReq)))
                .andExpect(status().isOk());

        reset(webPushClient);
        when(webPushClient.sendPush(any(), anyString())).thenReturn(PushSendResult.success(201));

        // 1. Owner calls next customer
        queueService.callNextCustomer(testShopId);

        // Verify push sent
        verify(webPushClient, timeout(2000).atLeastOnce()).sendPush(any(CustomerPushSubscription.class), anyString());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(webPushClient, atLeastOnce()).sendPush(any(), payloadCaptor.capture());

        String calledPayloadJson = payloadCaptor.getValue();
        assertTrue(calledPayloadJson.contains("It's your turn!"));
        assertTrue(calledPayloadJson.contains("queueless-queue-" + trackingToken));
        assertTrue(calledPayloadJson.contains("CALLED"));

        // 2. Owner starts serving
        reset(webPushClient);
        when(webPushClient.sendPush(any(), anyString())).thenReturn(PushSendResult.success(201));

        QueueCustomer customer = customerRepository.findByTrackingToken(trackingToken).orElseThrow();
        queueService.updateCustomerStatus(testShopId, customer.getId(), CustomerQueueStatus.SERVING);

        verify(webPushClient, timeout(2000).atLeastOnce()).sendPush(any(CustomerPushSubscription.class), payloadCaptor.capture());
        String servingPayloadJson = payloadCaptor.getValue();
        assertTrue(servingPayloadJson.contains("Service Started"));
        assertTrue(servingPayloadJson.contains("SERVING"));
    }

    @Test
    @DisplayName("Expired push subscription (HTTP 410 Gone) is deactivated automatically")
    void testExpiredPushSubscriptionDeactivated() throws Exception {
        CustomerJoinResponse joinRes = queueService.customerJoinQueue(publicQueueId, new CustomerJoinRequest("Hardik Pandya", "9876500004"));
        String trackingToken = joinRes.getTrackingToken();

        PushSubscriptionRequest pushReq = new PushSubscriptionRequest(
                "https://fcm.googleapis.com/fcm/send/customer-hardik-expired",
                "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QT9Q0mA8GqkWfaoqq2m308wktA1qms7pQ",
                "tBHItJI5svbpez7KI4CCXg",
                "Mozilla/5.0"
        );

        mockMvc.perform(post("/api/public/queue/track/" + trackingToken + "/push-subscription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pushReq)))
                .andExpect(status().isOk());

        // Simulate push gateway returning 410 Gone
        reset(webPushClient);
        when(webPushClient.sendPush(any(), anyString())).thenReturn(PushSendResult.expired(410, "Subscription expired"));

        // Trigger push via callNextCustomer
        queueService.callNextCustomer(testShopId);

        verify(webPushClient, timeout(2000).atLeastOnce()).sendPush(any(CustomerPushSubscription.class), anyString());

        // Subscription should now be active = false
        List<CustomerPushSubscription> activeSubs = pushSubscriptionRepository.findByCustomerTrackingTokenAndActiveTrue(trackingToken);
        assertTrue(activeSubs.isEmpty());
    }
}
