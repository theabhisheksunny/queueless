package com.queueless.controller;

import com.queueless.dto.CustomerEditRequest;
import com.queueless.dto.CustomerManualAddRequest;
import com.queueless.dto.QueueDashboardResponse;
import com.queueless.entity.CustomerQueueStatus;
import com.queueless.entity.Shop;
import com.queueless.entity.User;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.UserRepository;
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
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final EntitlementService entitlementService;

    public QueueController(QueueService queueService,
                           SseEmitterService sseEmitterService,
                           UserRepository userRepository,
                           ShopRepository shopRepository,
                           EntitlementService entitlementService) {
        this.queueService = queueService;
        this.sseEmitterService = sseEmitterService;
        this.userRepository = userRepository;
        this.shopRepository = shopRepository;
        this.entitlementService = entitlementService;
    }

    private Shop getShopFromPrincipal(Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("Authentication required");
        }
        User user = userRepository.findByIdentifier(principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + principal.getName()));
        return shopRepository.findByOwnerId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("No shop associated with user: " + principal.getName()));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<QueueDashboardResponse> getDashboard(Principal principal) {
        Shop shop = getShopFromPrincipal(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        return ResponseEntity.ok(queueService.getDashboard(shop.getId()));
    }

    @PostMapping("/start")
    public ResponseEntity<QueueDashboardResponse> startQueue(Principal principal) {
        Shop shop = getShopFromPrincipal(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        return ResponseEntity.ok(queueService.startQueue(shop.getId()));
    }

    @PostMapping("/close")
    public ResponseEntity<QueueDashboardResponse> closeQueue(Principal principal) {
        Shop shop = getShopFromPrincipal(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        return ResponseEntity.ok(queueService.closeQueue(shop.getId()));
    }

    @PostMapping("/customers/manual")
    public ResponseEntity<QueueDashboardResponse> manualAddCustomer(
            Principal principal,
            @Valid @RequestBody CustomerManualAddRequest request) {
        Shop shop = getShopFromPrincipal(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        return ResponseEntity.ok(queueService.manualAddCustomer(shop.getId(), request));
    }

    @PostMapping("/customers/next")
    public ResponseEntity<QueueDashboardResponse> callNextCustomer(Principal principal) {
        Shop shop = getShopFromPrincipal(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        return ResponseEntity.ok(queueService.callNextCustomer(shop.getId()));
    }

    @PatchMapping("/customers/{customerId}/status")
    public ResponseEntity<QueueDashboardResponse> updateCustomerStatus(
            Principal principal,
            @PathVariable Long customerId,
            @RequestParam CustomerQueueStatus status) {
        Shop shop = getShopFromPrincipal(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        return ResponseEntity.ok(queueService.updateCustomerStatus(shop.getId(), customerId, status));
    }

    @PutMapping("/customers/{customerId}")
    public ResponseEntity<QueueDashboardResponse> editCustomer(
            Principal principal,
            @PathVariable Long customerId,
            @Valid @RequestBody CustomerEditRequest request) {
        Shop shop = getShopFromPrincipal(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        return ResponseEntity.ok(queueService.editCustomer(shop.getId(), customerId, request));
    }

    @DeleteMapping("/customers/{customerId}")
    public ResponseEntity<QueueDashboardResponse> removeCustomer(
            Principal principal,
            @PathVariable Long customerId) {
        Shop shop = getShopFromPrincipal(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        return ResponseEntity.ok(queueService.removeCustomer(shop.getId(), customerId));
    }

    /**
     * Real-time Server-Sent Events stream for Owner dashboard.
     */
    @GetMapping(value = "/stream/owner", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOwnerUpdates(Principal principal) {
        Shop shop = getShopFromPrincipal(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        return sseEmitterService.createOwnerEmitter(shop.getId());
    }
}
