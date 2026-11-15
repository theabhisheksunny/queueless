package com.queueless.event;

import com.queueless.dto.PushNotificationPayload;
import com.queueless.service.push.CustomerPushNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class QueuePushEventListener {

    private static final Logger log = LoggerFactory.getLogger(QueuePushEventListener.class);

    private final CustomerPushNotificationService pushService;

    public QueuePushEventListener(CustomerPushNotificationService pushService) {
        this.pushService = pushService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueueEvent(QueueEvent event) {
        log.debug("Processing QueueEvent type: {} for tracking token: {}", event.getType(), event.getTrackingToken());

        switch (event.getType()) {
            case TOKEN_CALLED -> {
                String title = "It's your turn! — " + event.getShopName();
                String body = "Token #" + event.getTokenNumber() + ": Please reach the counter now!";
                PushNotificationPayload payload = PushNotificationPayload.createNotify(
                        title, body, event.getTrackingToken(), event.getTokenNumber(), "CALLED", 0
                );
                pushService.sendPushToCustomer(event.getTrackingToken(), payload);
            }
            case SERVICE_STARTED -> {
                String title = "Service Started — " + event.getShopName();
                String body = "Token #" + event.getTokenNumber() + ": Your service has started.";
                PushNotificationPayload payload = PushNotificationPayload.createNotify(
                        title, body, event.getTrackingToken(), event.getTokenNumber(), "SERVING", 0
                );
                pushService.sendPushToCustomer(event.getTrackingToken(), payload);
            }
            case SERVICE_COMPLETED -> {
                String title = "Service Completed — " + event.getShopName();
                String body = "Token #" + event.getTokenNumber() + ": Thank you for visiting " + event.getShopName() + "!";
                PushNotificationPayload payload = PushNotificationPayload.createNotify(
                        title, body, event.getTrackingToken(), event.getTokenNumber(), "COMPLETED", 0
                );
                pushService.sendPushToCustomer(event.getTrackingToken(), payload);
            }
            case POSITION_MILESTONE -> {
                int ahead = event.getPeopleAhead() != null ? event.getPeopleAhead() : 0;
                int waitMins = event.getEstimatedWaitMinutes() != null ? event.getEstimatedWaitMinutes() : 0;
                String title = "Queue Update — " + event.getShopName();
                String body = "Token #" + event.getTokenNumber() + ": You are now #" + (ahead + 1) + " in line (" + ahead + " ahead). Est. wait: ~" + waitMins + " mins.";
                PushNotificationPayload payload = PushNotificationPayload.createNotify(
                        title, body, event.getTrackingToken(), event.getTokenNumber(), "WAITING", ahead
                );
                pushService.sendPushToCustomer(event.getTrackingToken(), payload);
            }
            case CUSTOMER_LEFT, CUSTOMER_REMOVED -> {
                if (event.getTrackingToken() != null) {
                    pushService.closeCustomerNotification(event.getTrackingToken());
                }
            }
            case QUEUE_CLOSED -> {
                if (event.getShopId() != null) {
                    pushService.closeAllShopNotifications(event.getShopId());
                }
            }
            case CUSTOMER_JOINED -> {
                // Initial confirmation handled on registration if desired
            }
        }
    }
}
