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

        // Reject push registration if customer is in any terminal status
        CustomerQueueStatus status = customer.getStatus();
        if (status == CustomerQueueStatus.COMPLETED ||
            status == CustomerQueueStatus.CANCELLED ||
            status == CustomerQueueStatus.LEFT ||
            status == CustomerQueueStatus.NO_SHOW) {
            throw new IllegalStateException("Cannot register push notifications for a customer in " + status + " status.");
        }

        // Reject push registration if the queue session is closed
        if (customer.getQueueSession().getStatus() != com.queueless.entity.QueueSessionStatus.OPEN) {
            throw new IllegalStateException("Queue is currently closed. Cannot register push notifications.");
        }

        Shop shop = customer.getQueueSession().getShop();
        String endpoint = request.getEndpoint().trim();

        // If this exact endpoint is currently active under another customer, deactivate it to prevent cross-session contamination
        List<CustomerPushSubscription> existingWithEndpoint = subscriptionRepository.findByEndpoint(endpoint);
        for (CustomerPushSubscription existing : existingWithEndpoint) {
            if (!existing.getCustomer().getId().equals(customer.getId()) && existing.isActive()) {
                existing.setActive(false);
                subscriptionRepository.save(existing);
                log.info("Deactivated existing subscription on endpoint for prior customer {}", existing.getCustomer().getId());
            }
        }

        // Cap maximum active push subscriptions per customer session to prevent unbounded flooding DoS
        List<CustomerPushSubscription> currentActive = subscriptionRepository.findByCustomerIdAndActiveTrue(customer.getId());
        if (currentActive.size() >= 5) {
            CustomerPushSubscription oldest = currentActive.get(0);
            oldest.setActive(false);
            subscriptionRepository.save(oldest);
        }

        List<CustomerPushSubscription> existingSubs = subscriptionRepository
                .findByCustomerTrackingTokenAndEndpoint(trackingToken, endpoint);
        CustomerPushSubscription subscription;
        if (!existingSubs.isEmpty()) {
            subscription = existingSubs.get(0);
        } else {
            subscription = new CustomerPushSubscription(
                    customer,
                    shop,
                    endpoint,
                    request.getP256dh().trim(),
                    request.getAuth().trim(),
                    request.getUserAgent()
            );
        }

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
            boolean isCritical = "CALLED".equalsIgnoreCase(payload.getStatus());

            for (CustomerPushSubscription sub : activeSubs) {
                PushSendResult result = webPushClient.sendPush(sub, json);

                // Transient retry for critical CALLED notifications
                if (!result.isSuccess() && !result.isExpired() && isCritical) {
                    try {
                        Thread.sleep(300);
                        result = webPushClient.sendPush(sub, json);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }

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

        // Deactivate all subscriptions for this customer in database since they are in a terminal state
        List<CustomerPushSubscription> subs = subscriptionRepository.findByCustomerTrackingTokenAndActiveTrue(trackingToken);
        for (CustomerPushSubscription sub : subs) {
            sub.setActive(false);
        }
        subscriptionRepository.saveAll(subs);
    }

    @Transactional
    public void closeAllShopNotifications(Long shopId) {
        List<CustomerPushSubscription> subs = subscriptionRepository.findByShopIdAndActiveTrue(shopId);
        for (CustomerPushSubscription sub : subs) {
            String token = sub.getCustomer().getTrackingToken();
            if (token != null) {
                PushNotificationPayload closePayload = PushNotificationPayload.createClose(token);
                sendPushToCustomer(token, closePayload);
            }
            sub.setActive(false);
        }
        subscriptionRepository.saveAll(subs);
    }
}
