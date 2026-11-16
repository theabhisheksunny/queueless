package com.queueless;

import com.queueless.dto.CustomerEditRequest;
import com.queueless.dto.CustomerJoinRequest;
import com.queueless.dto.CustomerJoinResponse;
import com.queueless.dto.CustomerManualAddRequest;
import com.queueless.dto.QueueDashboardResponse;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.CustomerQueueStatus;
import com.queueless.model.CustomerPositionInfo;
import com.queueless.service.ActiveQueueManager;
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
    private ActiveQueueManager activeQueueManager;

    @Autowired
    private AuthService authService;

    @Autowired
    private TestRegistrationHelper testRegistrationHelper;

    private Long testShopId;

    @BeforeEach
    void setUp() {
        // Register a fresh shop for testing
        RegisterRequest registerReq = new RegisterRequest();
        registerReq.setFullName("Test Owner");
        registerReq.setEmail("testowner" + System.currentTimeMillis() + "@test.com");
        registerReq.setPassword("password123");
        registerReq.setPhone("9998887776");
        testRegistrationHelper.preparePin(registerReq);
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
        // CALLED customer must NOT be reported as currently serving
        assertNull(dashCalled.getCurrentlyServingToken());

        // Call next customer does NOT automatically complete current customer
        queueService.updateCustomerStatus(testShopId, custAId, CustomerQueueStatus.SERVING);
        QueueDashboardResponse dashServing = queueService.getDashboard(testShopId);
        assertEquals("001", dashServing.getCurrentlyServingToken());

        // Complete customer A
        queueService.updateCustomerStatus(testShopId, custAId, CustomerQueueStatus.COMPLETED);
        QueueDashboardResponse dashCompleted = queueService.getDashboard(testShopId);
        assertEquals(CustomerQueueStatus.COMPLETED, dashCompleted.getCustomers().get(0).getStatus());
        // COMPLETED customer must disappear from currently serving
        assertNull(dashCompleted.getCurrentlyServingToken());

        // Next customer B is called
        queueService.callNextCustomer(testShopId);
        QueueDashboardResponse dashB = queueService.getDashboard(testShopId);
        assertEquals(CustomerQueueStatus.CALLED, dashB.getCustomers().get(1).getStatus());
        // CALLED customer B must NOT be reported as currently serving
        assertNull(dashB.getCurrentlyServingToken());
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
    @DisplayName("Regression Test A, B, C: Strict lifecycle - CALLED is not SERVING, and COMPLETED clears serving")
    void testRegressionBasicServingAndComplete() {
        queueService.startQueue(testShopId);

        // 1. Add Rahul, token 001
        var addRes = queueService.manualAddCustomer(testShopId, new CustomerManualAddRequest("Rahul", "9876543210"));
        Long rahulId = addRes.getCustomers().get(0).getId();
        assertEquals("001", addRes.getCustomers().get(0).getTokenNumber());
        assertNull(addRes.getCurrentlyServingToken());

        // 2. Click Next Customer -> Rahul becomes CALLED
        var callRes = queueService.callNextCustomer(testShopId);
        assertEquals(CustomerQueueStatus.CALLED, callRes.getCustomers().get(0).getStatus());
        // Verify Rahul is NOT displayed as currently serving merely because CALLED
        assertNull(callRes.getCurrentlyServingToken());
        assertNull(callRes.getCurrentlyServingName());

        // 3. Click Start Serving -> Rahul becomes SERVING
        var serveRes = queueService.updateCustomerStatus(testShopId, rahulId, CustomerQueueStatus.SERVING);
        assertEquals(CustomerQueueStatus.SERVING, serveRes.getCustomers().get(0).getStatus());
        assertEquals("001", serveRes.getCurrentlyServingToken());
        assertEquals("Rahul", serveRes.getCurrentlyServingName());
        assertNotNull(serveRes.getCurrentlyServingCustomers());
        assertEquals(1, serveRes.getCurrentlyServingCustomers().size());

        // 4. Complete Rahul (Test B)
        var compRes = queueService.updateCustomerStatus(testShopId, rahulId, CustomerQueueStatus.COMPLETED);
        assertEquals(CustomerQueueStatus.COMPLETED, compRes.getCustomers().get(0).getStatus());
        assertNull(compRes.getCurrentlyServingToken());
        assertNull(compRes.getCurrentlyServingName());
        assertEquals(0, compRes.getWaitingCustomers());

        // 5. Add Amit, token 002 (Test C)
        var addAmit = queueService.manualAddCustomer(testShopId, new CustomerManualAddRequest("Amit", "9123456789"));
        Long amitId = addAmit.getCustomers().get(1).getId();
        assertEquals("002", addAmit.getCustomers().get(1).getTokenNumber());
        assertNull(addAmit.getCurrentlyServingToken());

        // Call Amit
        var callAmit = queueService.callNextCustomer(testShopId);
        assertEquals(CustomerQueueStatus.CALLED, callAmit.getCustomers().get(1).getStatus());
        assertNull(callAmit.getCurrentlyServingToken());

        // Start serving Amit
        var serveAmit = queueService.updateCustomerStatus(testShopId, amitId, CustomerQueueStatus.SERVING);
        assertEquals(CustomerQueueStatus.SERVING, serveAmit.getCustomers().get(1).getStatus());
        assertEquals("002", serveAmit.getCurrentlyServingToken());
        assertEquals("Amit", serveAmit.getCurrentlyServingName());
    }

    @Test
    @DisplayName("Regression Test D: Multiple serving customers simultaneously")
    void testRegressionMultipleServingCustomers() {
        queueService.startQueue(testShopId);

        var r1 = queueService.manualAddCustomer(testShopId, new CustomerManualAddRequest("Rahul", ""));
        var r2 = queueService.manualAddCustomer(testShopId, new CustomerManualAddRequest("Amit", ""));
        Long rahulId = r1.getCustomers().get(0).getId();
        Long amitId = r2.getCustomers().get(1).getId();

        // Start serving Rahul
        queueService.updateCustomerStatus(testShopId, rahulId, CustomerQueueStatus.SERVING);
        // Start serving Amit
        var multiServe = queueService.updateCustomerStatus(testShopId, amitId, CustomerQueueStatus.SERVING);

        // Verify both are represented correctly
        assertTrue(multiServe.getCurrentlyServingToken().contains("001"));
        assertTrue(multiServe.getCurrentlyServingToken().contains("002"));
        assertTrue(multiServe.getCurrentlyServingName().contains("Rahul"));
        assertTrue(multiServe.getCurrentlyServingName().contains("Amit"));
        assertEquals(2, multiServe.getCurrentlyServingCustomers().size());

        // Complete Rahul
        var afterRahulComp = queueService.updateCustomerStatus(testShopId, rahulId, CustomerQueueStatus.COMPLETED);
        // Verify Amit remains displayed as SERVING
        assertEquals("002", afterRahulComp.getCurrentlyServingToken());
        assertEquals("Amit", afterRahulComp.getCurrentlyServingName());
        assertEquals(1, afterRahulComp.getCurrentlyServingCustomers().size());
        assertEquals("002", afterRahulComp.getCurrentlyServingCustomers().get(0).getTokenNumber());
    }

    @Test
    @DisplayName("Regression Test F: State recovery after restart / reload from DB")
    void testRegressionStateRecoveryFromDb() {
        queueService.startQueue(testShopId);
        var r1 = queueService.manualAddCustomer(testShopId, new CustomerManualAddRequest("Rahul", ""));
        Long rahulId = r1.getCustomers().get(0).getId();
        queueService.updateCustomerStatus(testShopId, rahulId, CustomerQueueStatus.SERVING);

        // Simulate new in-memory active queue re-initialized from DB
        activeQueueManager.resetQueueForNewSession(testShopId, null, null);
        var freshDash = queueService.getDashboard(testShopId);

        // Verify Rahul is still SERVING
        assertEquals("001", freshDash.getCurrentlyServingToken());
        assertEquals("Rahul", freshDash.getCurrentlyServingName());
        assertEquals(CustomerQueueStatus.SERVING, freshDash.getCustomers().get(0).getStatus());
    }
}
