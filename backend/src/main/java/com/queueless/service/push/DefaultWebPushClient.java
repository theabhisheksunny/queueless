package com.queueless.service.push;

import com.queueless.entity.CustomerPushSubscription;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class DefaultWebPushClient implements WebPushClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultWebPushClient.class);

    private final VapidKeyProvider vapidKeyProvider;

    public DefaultWebPushClient(VapidKeyProvider vapidKeyProvider) {
        this.vapidKeyProvider = vapidKeyProvider;
    }

    @Override
    public PushSendResult sendPush(CustomerPushSubscription subscription, String payloadJson) {
        if (subscription == null || subscription.getEndpoint() == null || subscription.getEndpoint().isBlank()) {
            return PushSendResult.failure(400, "Invalid subscription endpoint");
        }

        try {
            PushService pushService = new PushService();
            pushService.setPublicKey(vapidKeyProvider.getPublicKey());
            pushService.setPrivateKey(vapidKeyProvider.getPrivateKey());
            pushService.setSubject(vapidKeyProvider.getSubject());

            byte[] payloadBytes = payloadJson != null ? payloadJson.getBytes(StandardCharsets.UTF_8) : new byte[0];

            Notification notification = new Notification(
                    subscription.getEndpoint(),
                    subscription.getP256dhKey(),
                    subscription.getAuthSecret(),
                    payloadBytes
            );

            HttpResponse response = pushService.send(notification);
            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode == 200 || statusCode == 201) {
                return PushSendResult.success(statusCode);
            } else if (statusCode == 404 || statusCode == 410) {
                log.info("Push subscription expired (HTTP {}): {}", statusCode, subscription.getEndpoint());
                return PushSendResult.expired(statusCode, "Subscription expired or unsubscribed");
            } else {
                log.warn("Push delivery returned HTTP {}: {}", statusCode, subscription.getEndpoint());
                return PushSendResult.failure(statusCode, "Push service returned HTTP " + statusCode);
            }
        } catch (Exception e) {
            log.error("Failed to deliver Web Push to endpoint {}: {}", subscription.getEndpoint(), e.getMessage());
            return PushSendResult.failure(500, e.getMessage());
        }
    }
}
