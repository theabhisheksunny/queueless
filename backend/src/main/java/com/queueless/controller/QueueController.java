package com.queueless.controller;

import com.queueless.dto.CustomerEditRequest;
import com.queueless.dto.CustomerManualAddRequest;
import com.queueless.dto.QueueDashboardResponse;
import com.queueless.entity.CustomerQueueStatus;
import com.queueless.entity.Shop;
import com.queueless.service.AuthenticatedShopResolver;
import com.queueless.service.EntitlementService;
import com.queueless.service.QueueService;
import com.queueless.service.SseEmitterService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.Principal;

@RestController
@RequestMapping("/api/queue")
public class QueueController {

    private final QueueService queueService;
    private final SseEmitterService sseEmitterService;
    private final AuthenticatedShopResolver shopResolver;
    private final EntitlementService entitlementService;

    public QueueController(QueueService queueService,
                           SseEmitterService sseEmitterService,
                           AuthenticatedShopResolver shopResolver,
                           EntitlementService entitlementService) {
        this.queueService = queueService;
        this.sseEmitterService = sseEmitterService;
        this.shopResolver = shopResolver;
        this.entitlementService = entitlementService;
    }

    private Shop resolveTargetShop(Principal principal, Long paramShopId, Long headerShopId) {
        Long target = paramShopId != null ? paramShopId : headerShopId;
        Shop shop = shopResolver.resolveShop(principal, target);
        entitlementService.requireActiveEntitlement(shop.getId());
        return shop;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<QueueDashboardResponse> getDashboard(
            Principal principal,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId) {
        Shop shop = resolveTargetShop(principal, shopId, headerShopId);
        return ResponseEntity.ok(queueService.getDashboard(shop.getId()));
    }

    @PostMapping("/start")
    public ResponseEntity<QueueDashboardResponse> startQueue(
            Principal principal,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId) {
        Shop shop = resolveTargetShop(principal, shopId, headerShopId);
        return ResponseEntity.ok(queueService.startQueue(shop.getId()));
    }

    @PostMapping("/close")
    public ResponseEntity<QueueDashboardResponse> closeQueue(
            Principal principal,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId) {
        Shop shop = resolveTargetShop(principal, shopId, headerShopId);
        return ResponseEntity.ok(queueService.closeQueue(shop.getId()));
    }

    @PostMapping("/customers/manual")
    public ResponseEntity<QueueDashboardResponse> manualAddCustomer(
            Principal principal,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId,
            @Valid @RequestBody CustomerManualAddRequest request) {
        Shop shop = resolveTargetShop(principal, shopId, headerShopId);
        return ResponseEntity.ok(queueService.manualAddCustomer(shop.getId(), request));
    }

    @PostMapping("/customers/next")
    public ResponseEntity<QueueDashboardResponse> callNextCustomer(
            Principal principal,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId) {
        Shop shop = resolveTargetShop(principal, shopId, headerShopId);
        return ResponseEntity.ok(queueService.callNextCustomer(shop.getId()));
    }

    @PatchMapping("/customers/{customerId}/status")
    public ResponseEntity<QueueDashboardResponse> updateCustomerStatus(
            Principal principal,
            @PathVariable Long customerId,
            @RequestParam CustomerQueueStatus status,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId) {
        Shop shop = resolveTargetShop(principal, shopId, headerShopId);
        return ResponseEntity.ok(queueService.updateCustomerStatus(shop.getId(), customerId, status));
    }

    @PutMapping("/customers/{customerId}")
    public ResponseEntity<QueueDashboardResponse> editCustomer(
            Principal principal,
            @PathVariable Long customerId,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId,
            @Valid @RequestBody CustomerEditRequest request) {
        Shop shop = resolveTargetShop(principal, shopId, headerShopId);
        return ResponseEntity.ok(queueService.editCustomer(shop.getId(), customerId, request));
    }

    @DeleteMapping("/customers/{customerId}")
    public ResponseEntity<QueueDashboardResponse> removeCustomer(
            Principal principal,
            @PathVariable Long customerId,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId) {
        Shop shop = resolveTargetShop(principal, shopId, headerShopId);
        return ResponseEntity.ok(queueService.removeCustomer(shop.getId(), customerId));
    }

    /**
     * Real-time Server-Sent Events stream for Owner and Staff dashboard.
     */
    @GetMapping(value = "/stream/owner", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOwnerUpdates(
            Principal principal,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId) {
        Shop shop = resolveTargetShop(principal, shopId, headerShopId);
        return sseEmitterService.createOwnerEmitter(shop.getId());
    }
}
