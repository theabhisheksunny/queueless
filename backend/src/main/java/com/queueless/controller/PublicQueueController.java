package com.queueless.controller;

import com.queueless.dto.CustomerJoinRequest;
import com.queueless.dto.CustomerJoinResponse;
import com.queueless.dto.PublicQueueInfoResponse;
import com.queueless.dto.PushSubscriptionRequest;
import com.queueless.dto.VapidPublicKeyResponse;
import com.queueless.model.CustomerPositionInfo;
import com.queueless.service.QueueService;
import com.queueless.service.SseEmitterService;
import com.queueless.service.push.CustomerPushNotificationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/public/queue")
public class PublicQueueController {

    private final QueueService queueService;
    private final SseEmitterService sseEmitterService;
    private final CustomerPushNotificationService pushService;

    public PublicQueueController(QueueService queueService,
                                 SseEmitterService sseEmitterService,
                                 CustomerPushNotificationService pushService) {
        this.queueService = queueService;
        this.sseEmitterService = sseEmitterService;
        this.pushService = pushService;
    }

    /**
     * Customer previews shop queue info (shop name, category, open status) before joining.
     */
    @GetMapping("/{publicQueueId}/info")
    public ResponseEntity<PublicQueueInfoResponse> getPublicQueueInfo(@PathVariable String publicQueueId) {
        PublicQueueInfoResponse response = queueService.getPublicQueueInfo(publicQueueId);
        return ResponseEntity.ok(response);
    }

    /**
     * Canonical Permanent Merchant URL: Customer previews queue info by permanent opaque merchant code.
     */
    @GetMapping("/shop/{merchantCode}/info")
    public ResponseEntity<PublicQueueInfoResponse> getCanonicalShopQueueInfo(@PathVariable String merchantCode) {
        PublicQueueInfoResponse response = queueService.getCanonicalShopQueueInfo(merchantCode);
        return ResponseEntity.ok(response);
    }

    /**
     * Customer scans QR code and joins the queue.
     */
    @PostMapping("/{publicQueueId}/join")
    public ResponseEntity<CustomerJoinResponse> joinQueue(
            @PathVariable String publicQueueId,
            @Valid @RequestBody CustomerJoinRequest request) {
        CustomerJoinResponse response = queueService.customerJoinQueue(publicQueueId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Canonical Permanent Merchant URL: Customer joins queue by permanent opaque merchant code.
     */
    @PostMapping("/shop/{merchantCode}/join")
    public ResponseEntity<CustomerJoinResponse> canonicalShopJoinQueue(
            @PathVariable String merchantCode,
            @Valid @RequestBody CustomerJoinRequest request) {
        CustomerJoinResponse response = queueService.canonicalShopJoinQueue(merchantCode, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Customer checks token position and estimated wait time.
     */
    @GetMapping("/track/{trackingToken}")
    public ResponseEntity<CustomerPositionInfo> trackPosition(@PathVariable String trackingToken) {
        CustomerPositionInfo positionInfo = queueService.getCustomerTrackingInfo(trackingToken);
        return ResponseEntity.ok(positionInfo);
    }

    /**
     * Customer voluntarily leaves the queue from tracking page.
     */
    @PostMapping("/track/{trackingToken}/leave")
    public ResponseEntity<CustomerPositionInfo> leaveQueue(@PathVariable String trackingToken) {
        CustomerPositionInfo updated = queueService.customerLeaveQueue(trackingToken);
        return ResponseEntity.ok(updated);
    }

    /**
     * Optional rejoin queue.
     */
    @PostMapping("/track/{trackingToken}/rejoin")
    public ResponseEntity<CustomerPositionInfo> rejoinQueue(@PathVariable String trackingToken) {
        CustomerPositionInfo updated = queueService.customerRejoinQueue(trackingToken);
        return ResponseEntity.ok(updated);
    }

    /**
     * Real-time Server-Sent Events stream for customer tracking page.
     * Enforces the 15-minute completed token expiration policy.
     */
    @GetMapping(value = "/stream/customer/{trackingToken}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCustomerUpdates(@PathVariable String trackingToken) {
        queueService.validateTrackingTokenNotExpired(trackingToken);
        return sseEmitterService.createCustomerEmitter(trackingToken);
    }

    /**
     * Returns VAPID public key for Web Push subscription.
     */
    @GetMapping("/vapid-public-key")
    public ResponseEntity<VapidPublicKeyResponse> getVapidPublicKey() {
        String key = pushService.getVapidPublicKey();
        return ResponseEntity.ok(new VapidPublicKeyResponse(key));
    }

    /**
     * Registers a Web Push subscription for this customer session.
     */
    @PostMapping("/track/{trackingToken}/push-subscription")
    public ResponseEntity<Map<String, Object>> registerPushSubscription(
            @PathVariable String trackingToken,
            @Valid @RequestBody PushSubscriptionRequest request) {
        queueService.validateTrackingTokenNotExpired(trackingToken);
        pushService.registerSubscription(trackingToken, request);
        return ResponseEntity.ok(Map.of("success", true, "message", "Push notification subscribed"));
    }

    /**
     * Unregisters Web Push subscription for this customer session.
     */
    @DeleteMapping("/track/{trackingToken}/push-subscription")
    public ResponseEntity<Map<String, Object>> unregisterPushSubscription(
            @PathVariable String trackingToken) {
        pushService.unregisterSubscription(trackingToken);
        return ResponseEntity.ok(Map.of("success", true, "message", "Push notification unsubscribed"));
    }
}
