package com.queueless.service.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.PushNotificationPayload;
import com.queueless.dto.PushSubscriptionRequest;
import com.queueless.entity.CustomerPushSubscription;
import com.queueless.entity.CustomerQueueStatus;
import com.queueless.entity.QueueCustomer;
import com.queueless.entity.Shop;
import com.queueless.repository.CustomerPushSubscriptionRepository;
import com.queueless.repository.QueueCustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CustomerPushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(CustomerPushNotificationService.class);

    private final CustomerPushSubscriptionRepository subscriptionRepository;
    private final QueueCustomerRepository customerRepository;
    private final VapidKeyProvider vapidKeyProvider;
    private final WebPushClient webPushClient;
    private final ObjectMapper objectMapper;

    public CustomerPushNotificationService(CustomerPushSubscriptionRepository subscriptionRepository,
                                           QueueCustomerRepository customerRepository,
                                           VapidKeyProvider vapidKeyProvider,
                                           WebPushClient webPushClient,
                                           ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.customerRepository = customerRepository;
        this.vapidKeyProvider = vapidKeyProvider;
        this.webPushClient = webPushClient;
        this.objectMapper = objectMapper;
    }

    public String getVapidPublicKey() {
        return vapidKeyProvider.getPublicKeyBase64();
    }

    @Transactional
    public void registerSubscription(String trackingToken, PushSubscriptionRequest request) {
        if (trackingToken == null || trackingToken.isBlank()) {
            throw new IllegalArgumentException("Tracking token is required");
        }

        QueueCustomer customer = customerRepository.findByTrackingToken(trackingToken)
                .orElseThrow(() -> new IllegalArgumentException("Customer tracking token not found"));

        if (customer.getStatus() == CustomerQueueStatus.COMPLETED) {
            // Check if completed token is already expired
            LocalDateTime completedAt = customer.getCompletedAt();
            if (completedAt != null && LocalDateTime.now().isAfter(completedAt.plusMinutes(15))) {
                throw new IllegalStateException("Your token has expired. Cannot register push notifications.");
            }
        }

        Shop shop = customer.getQueueSession().getShop();
        String endpoint = request.getEndpoint().trim();

        CustomerPushSubscription subscription = subscriptionRepository
                .findByCustomerTrackingTokenAndEndpoint(trackingToken, endpoint)
                .orElseGet(() -> new CustomerPushSubscription(
                        customer,
                        shop,
                        endpoint,
                        request.getP256dh().trim(),
                        request.getAuth().trim(),
                        request.getUserAgent()
                ));

        subscription.setP256dhKey(request.getP256dh().trim());
        subscription.setAuthSecret(request.getAuth().trim());
        subscription.setUserAgent(request.getUserAgent());
        subscription.setActive(true);
        subscriptionRepository.save(subscription);

        log.info("Registered push subscription for customer {} (Token #{})", customer.getCustomerName(), customer.getTokenNumber());
    }

    @Transactional
    public void unregisterSubscription(String trackingToken) {
        List<CustomerPushSubscription> subs = subscriptionRepository.findByCustomerTrackingToken(trackingToken);
        for (CustomerPushSubscription sub : subs) {
            sub.setActive(false);
        }
        subscriptionRepository.saveAll(subs);
        log.info("Unregistered push subscriptions for tracking token {}", trackingToken);
    }

    @Transactional
    public void sendPushToCustomer(String trackingToken, PushNotificationPayload payload) {
        List<CustomerPushSubscription> activeSubs = subscriptionRepository.findByCustomerTrackingTokenAndActiveTrue(trackingToken);
        if (activeSubs.isEmpty()) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(payload);
            LocalDateTime now = LocalDateTime.now();

            for (CustomerPushSubscription sub : activeSubs) {
                PushSendResult result = webPushClient.sendPush(sub, json);
                sub.setLastPushAt(now);
                sub.setLastPushStatus(result.isSuccess() ? "SUCCESS" : (result.isExpired() ? "EXPIRED" : "FAILED"));

                if (result.isExpired()) {
                    sub.setActive(false);
                }
                subscriptionRepository.save(sub);
            }
        } catch (Exception e) {
            log.error("Failed to send push to tracking token {}: {}", trackingToken, e.getMessage());
        }
    }

    @Transactional
    public void closeCustomerNotification(String trackingToken) {
        PushNotificationPayload closePayload = PushNotificationPayload.createClose(trackingToken);
        sendPushToCustomer(trackingToken, closePayload);
    }

    @Transactional
    public void closeAllShopNotifications(Long shopId) {
        List<CustomerPushSubscription> subs = subscriptionRepository.findByShopIdAndActiveTrue(shopId);
        for (CustomerPushSubscription sub : subs) {
            String token = sub.getCustomer().getTrackingToken();
            if (token != null) {
                closeCustomerNotification(token);
            }
        }
    }
}
