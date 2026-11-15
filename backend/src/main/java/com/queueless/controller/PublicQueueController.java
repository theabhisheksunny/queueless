package com.queueless.controller;

import com.queueless.dto.CustomerJoinRequest;
import com.queueless.dto.CustomerJoinResponse;
import com.queueless.model.CustomerPositionInfo;
import com.queueless.service.QueueService;
import com.queueless.service.SseEmitterService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/public/queue")
public class PublicQueueController {

    private final QueueService queueService;
    private final SseEmitterService sseEmitterService;

    public PublicQueueController(QueueService queueService, SseEmitterService sseEmitterService) {
        this.queueService = queueService;
        this.sseEmitterService = sseEmitterService;
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
     */
    @GetMapping(value = "/stream/customer/{trackingToken}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCustomerUpdates(@PathVariable String trackingToken) {
        return sseEmitterService.createCustomerEmitter(trackingToken);
    }
}
