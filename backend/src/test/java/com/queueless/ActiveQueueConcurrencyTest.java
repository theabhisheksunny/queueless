package com.queueless;

import com.queueless.dto.CustomerJoinRequest;
import com.queueless.dto.CustomerJoinResponse;
import com.queueless.dto.QueueDashboardResponse;
import com.queueless.dto.RegisterRequest;
import com.queueless.service.AuthService;
import com.queueless.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ActiveQueueConcurrencyTest {

    @Autowired
    private QueueService queueService;

    @Autowired
    private AuthService authService;

    private Long testShopId;
    private String publicQueueId;

    @BeforeEach
    void setUp() {
        RegisterRequest registerReq = new RegisterRequest();
        registerReq.setFullName("Concurrency Tester");
        registerReq.setEmail("concurrency" + System.currentTimeMillis() + "@test.com");
        registerReq.setPassword("password123");
        registerReq.setPhone("9876543299");
        registerReq.setShopName("High Volume Barber Clinic");
        registerReq.setCategory("Barber");
        registerReq.setAvgServiceDurationMinutes(10);

        var authRes = authService.register(registerReq);
        testShopId = authRes.getShopId();

        QueueDashboardResponse dash = queueService.startQueue(testShopId);
        publicQueueId = dash.getPublicQueueId();
    }

    @Test
    @DisplayName("Concurrent customer joining (120 parallel requests) produces unique sequential tokens without gaps or duplicates")
    void testConcurrentCustomerJoiningMoreThan100Customers() throws InterruptedException {
        int customerCount = 120;
        int threadPoolSize = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        CountDownLatch doneLatch = new CountDownLatch(customerCount);

        Set<String> allocatedTokens = ConcurrentHashMap.newKeySet();
        Set<String> trackingTokens = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 1; i <= customerCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    CustomerJoinRequest req = new CustomerJoinRequest("Concurrent Customer " + index, "999000" + String.format("%04d", index));
                    CustomerJoinResponse res = queueService.customerJoinQueue(publicQueueId, req);

                    allocatedTokens.add(res.getTokenNumber());
                    trackingTokens.add(res.getTrackingToken());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "All concurrent requests should finish within 30 seconds");
        assertTrue(errors.isEmpty(), "No errors should occur during concurrent joining: " + errors);

        // Verify strictly unique 120 tokens
        assertEquals(customerCount, allocatedTokens.size(), "All 120 tokens must be unique!");
        assertEquals(customerCount, trackingTokens.size(), "All 120 tracking tokens must be unique!");

        // Verify range includes 001, 100, 120
        assertTrue(allocatedTokens.contains("001"), "Must contain token 001");
        assertTrue(allocatedTokens.contains("100"), "Must contain token 100");
        assertTrue(allocatedTokens.contains("120"), "Must contain token 120");

        // Verify dashboard reflects full queue
        QueueDashboardResponse dashboard = queueService.getDashboard(testShopId);
        assertEquals(120, dashboard.getTotalCustomers());
        assertEquals(120, dashboard.getWaitingCustomers());
    }
}
