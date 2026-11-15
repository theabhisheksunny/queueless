package com.queueless;

import com.queueless.dto.CustomerEditRequest;
import com.queueless.dto.CustomerJoinRequest;
import com.queueless.dto.CustomerJoinResponse;
import com.queueless.dto.CustomerManualAddRequest;
import com.queueless.dto.QueueDashboardResponse;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.CustomerQueueStatus;
import com.queueless.model.CustomerPositionInfo;
import com.queueless.service.AuthService;
import com.queueless.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class QueueServiceTest {

    @Autowired
    private QueueService queueService;

    @Autowired
    private AuthService authService;

    private Long testShopId;

    @BeforeEach
    void setUp() {
        // Register a fresh shop for testing
        RegisterRequest registerReq = new RegisterRequest();
        registerReq.setFullName("Test Owner");
        registerReq.setEmail("testowner" + System.currentTimeMillis() + "@test.com");
        registerReq.setPassword("password123");
        registerReq.setPhone("9998887776");
        registerReq.setShopName("Aesthetic Hair Salon");
        registerReq.setCategory("Salon");
        registerReq.setAvgServiceDurationMinutes(15);

        var authRes = authService.register(registerReq);
        testShopId = authRes.getShopId();
    }

    @Test
    @DisplayName("Start Queue creates session with unique publicQueueId and status OPEN")
    void testStartQueue() {
        QueueDashboardResponse dashboard = queueService.startQueue(testShopId);

        assertNotNull(dashboard);
        assertTrue(dashboard.isQueueOpen());
        assertNotNull(dashboard.getPublicQueueId());
        assertTrue(dashboard.getPublicQueueId().startsWith("QS-"));
        assertEquals(0, dashboard.getTotalCustomers());
        assertEquals(0, dashboard.getWaitingCustomers());
    }

    @Test
    @DisplayName("Manual Customer Addition allocates sequential tokens (001, 002...) and updates dashboard")
    void testManualCustomerAddition() {
        queueService.startQueue(testShopId);

        CustomerManualAddRequest c1 = new CustomerManualAddRequest("Aarav Patel", "9876543210");
        QueueDashboardResponse dash1 = queueService.manualAddCustomer(testShopId, c1);

        assertEquals(1, dash1.getTotalCustomers());
        assertEquals(1, dash1.getWaitingCustomers());
        assertEquals("001", dash1.getCustomers().get(0).getTokenNumber());
        assertEquals("Aarav Patel", dash1.getCustomers().get(0).getCustomerName());
        assertEquals(CustomerQueueStatus.WAITING, dash1.getCustomers().get(0).getStatus());

        CustomerManualAddRequest c2 = new CustomerManualAddRequest("Priya Singh", "9876543211");
        QueueDashboardResponse dash2 = queueService.manualAddCustomer(testShopId, c2);

        assertEquals(2, dash2.getTotalCustomers());
        assertEquals(2, dash2.getWaitingCustomers());
        assertEquals("002", dash2.getCustomers().get(1).getTokenNumber());
    }

    @Test
    @DisplayName("Customer joins via QR code, gets token and accurate tracking position")
    void testCustomerJoinViaQrAndTrack() {
        QueueDashboardResponse dash = queueService.startQueue(testShopId);
        String publicQueueId = dash.getPublicQueueId();

        // Customer 1 joins via QR
        CustomerJoinRequest req1 = new CustomerJoinRequest("Customer One", "9111111111");
        CustomerJoinResponse res1 = queueService.customerJoinQueue(publicQueueId, req1);

        assertNotNull(res1.getTrackingToken());
        assertEquals("001", res1.getTokenNumber());
        assertEquals(0, res1.getPeopleAhead());
        assertEquals(0, res1.getEstimatedWaitMinutes());

        // Customer 2 joins via QR
        CustomerJoinRequest req2 = new CustomerJoinRequest("Customer Two", "9222222222");
        CustomerJoinResponse res2 = queueService.customerJoinQueue(publicQueueId, req2);

        assertEquals("002", res2.getTokenNumber());
        assertEquals(1, res2.getPeopleAhead());
        assertEquals(15, res2.getEstimatedWaitMinutes()); // 1 * 15 minutes avg service duration

        // Verify tracking info endpoint
        CustomerPositionInfo track2 = queueService.getCustomerTrackingInfo(res2.getTrackingToken());
        assertEquals("002", track2.getTokenNumber());
        assertEquals(1, track2.getPeopleAhead());
        assertEquals(15, track2.getEstimatedWaitMinutes());
        assertEquals(CustomerQueueStatus.WAITING, track2.getStatus());
    }

    @Test
    @DisplayName("Call Next Customer, Start Serving, and Complete Customer lifecycle")
    void testQueueLifecycleCallServeComplete() {
        queueService.startQueue(testShopId);

        queueService.manualAddCustomer(testShopId, new CustomerManualAddRequest("Customer A", ""));
        queueService.manualAddCustomer(testShopId, new CustomerManualAddRequest("Customer B", ""));

        // Call Next Customer (FIFO -> Customer A)
        QueueDashboardResponse dashCalled = queueService.callNextCustomer(testShopId);
        Long custAId = dashCalled.getCustomers().get(0).getId();
        assertEquals(CustomerQueueStatus.CALLED, dashCalled.getCustomers().get(0).getStatus());

        // Call next customer does NOT automatically complete current customer
        queueService.updateCustomerStatus(testShopId, custAId, CustomerQueueStatus.SERVING);
        QueueDashboardResponse dashServing = queueService.getDashboard(testShopId);
        assertEquals("001", dashServing.getCurrentlyServingToken());

        // Complete customer A
        queueService.updateCustomerStatus(testShopId, custAId, CustomerQueueStatus.COMPLETED);
        QueueDashboardResponse dashCompleted = queueService.getDashboard(testShopId);
        assertEquals(CustomerQueueStatus.COMPLETED, dashCompleted.getCustomers().get(0).getStatus());

        // Next customer B is called
        queueService.callNextCustomer(testShopId);
        QueueDashboardResponse dashB = queueService.getDashboard(testShopId);
        assertEquals(CustomerQueueStatus.CALLED, dashB.getCustomers().get(1).getStatus());
    }

    @Test
    @DisplayName("Edit and Remove customer from active queue")
    void testEditAndRemoveCustomer() {
        queueService.startQueue(testShopId);
        QueueDashboardResponse dash = queueService.manualAddCustomer(testShopId, new CustomerManualAddRequest("Original Name", "1111111111"));
        Long customerId = dash.getCustomers().get(0).getId();

        // Edit customer
        queueService.editCustomer(testShopId, customerId, new CustomerEditRequest("Updated Name", "9999999999"));
        QueueDashboardResponse dashEdited = queueService.getDashboard(testShopId);
        assertEquals("Updated Name", dashEdited.getCustomers().get(0).getCustomerName());
        assertEquals("9999999999", dashEdited.getCustomers().get(0).getCustomerPhone());

        // Remove customer
        queueService.removeCustomer(testShopId, customerId);
        QueueDashboardResponse dashRemoved = queueService.getDashboard(testShopId);
        assertEquals(CustomerQueueStatus.CANCELLED, dashRemoved.getCustomers().get(0).getStatus());
    }

    @Test
    @DisplayName("Customer leaves queue and updates subsequent customer wait times")
    void testCustomerLeavingQueue() {
        QueueDashboardResponse dash = queueService.startQueue(testShopId);
        String publicQueueId = dash.getPublicQueueId();

        var res1 = queueService.customerJoinQueue(publicQueueId, new CustomerJoinRequest("First", ""));
        var res2 = queueService.customerJoinQueue(publicQueueId, new CustomerJoinRequest("Second", ""));

        // Second customer has 1 person ahead
        CustomerPositionInfo beforeLeave = queueService.getCustomerTrackingInfo(res2.getTrackingToken());
        assertEquals(1, beforeLeave.getPeopleAhead());

        // First customer leaves queue
        queueService.customerLeaveQueue(res1.getTrackingToken());

        // Second customer should now have 0 people ahead
        CustomerPositionInfo afterLeave = queueService.getCustomerTrackingInfo(res2.getTrackingToken());
        assertEquals(0, afterLeave.getPeopleAhead());
        assertEquals(0, afterLeave.getEstimatedWaitMinutes());
    }

    @Test
    @DisplayName("Close Queue prevents new customers from joining")
    void testCloseQueue() {
        QueueDashboardResponse dash = queueService.startQueue(testShopId);
        String publicQueueId = dash.getPublicQueueId();

        queueService.closeQueue(testShopId);

        assertThrows(IllegalStateException.class, () -> {
            queueService.customerJoinQueue(publicQueueId, new CustomerJoinRequest("Late Customer", ""));
        });
    }
}
