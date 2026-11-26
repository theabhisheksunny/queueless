package com.queueless.controller;

import com.queueless.dto.CustomerEditRequest;
import com.queueless.dto.CustomerManualAddRequest;
import com.queueless.dto.QueueDashboardResponse;
import com.queueless.dto.SseTicketResponse;
import com.queueless.entity.CustomerQueueStatus;
import com.queueless.entity.Shop;
import com.queueless.service.AuthenticatedShopResolver;
import com.queueless.service.EntitlementService;
import com.queueless.service.QueueService;
import com.queueless.service.SseEmitterService;
import com.queueless.service.SseTicketService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
    private final SseTicketService sseTicketService;

    public QueueController(QueueService queueService,
                           SseEmitterService sseEmitterService,
                           AuthenticatedShopResolver shopResolver,
                           EntitlementService entitlementService,
                           SseTicketService sseTicketService) {
        this.queueService = queueService;
        this.sseEmitterService = sseEmitterService;
        this.shopResolver = shopResolver;
        this.entitlementService = entitlementService;
        this.sseTicketService = sseTicketService;
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
     * Issues an ephemeral, single-use ticket for owner/staff SSE stream connection.
     * Prevents long-lived JWT bearer tokens from being exposed in URL query parameters.
     */
    @PostMapping("/sse-ticket")
    public ResponseEntity<SseTicketResponse> createSseTicket(
            Principal principal,
            @RequestParam(value = "shopId", required = false) Long shopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId) {
        Shop shop = resolveTargetShop(principal, shopId, headerShopId);
        com.queueless.entity.User user = shopResolver.resolveUser(principal);
        SseTicketService.SseTicket ticket = sseTicketService.issueTicket(user.getId(), shop.getId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new SseTicketResponse(ticket.ticket(), SseTicketService.TICKET_TTL_SECONDS));
    }

    /**
     * Real-time Server-Sent Events stream for Owner and Staff dashboard.
     * Supports:
     * 1. Direct header authentication (Authorization: Bearer <jwt>) via fetch-based streaming. Zero URI exposure.
     * 2. Header-based ephemeral ticket (X-SSE-Ticket: stk_...). Zero URI exposure.
     * 3. Query parameter single-use ticket (?ticket=stk_...) for legacy native EventSource fallback.
     * Long-lived JWT tokens are explicitly forbidden as URL parameters.
     */
    @GetMapping(value = {"/stream/owner", "/stream", "/{shopId}/stream"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOwnerUpdates(
            Principal principal,
            @PathVariable(value = "shopId", required = false) Long pathShopId,
            @RequestParam(value = "ticket", required = false) String ticket,
            @RequestHeader(value = "X-SSE-Ticket", required = false) String headerTicket,
            @RequestParam(value = "shopId", required = false) Long paramShopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId,
            HttpServletResponse response) {
        if (response != null) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
            response.setHeader("X-Accel-Buffering", "no");
        }
        Long requestedShop = pathShopId != null ? pathShopId : (paramShopId != null ? paramShopId : headerShopId);
        Long targetShopId;

        // Priority 1: Direct Header-Authenticated Streaming (No credentials in URI)
        if (principal != null) {
            Shop shop = resolveTargetShop(principal, requestedShop, null);
            targetShopId = shop.getId();
        } else {
            // Priority 2: Ephemeral Single-Use Ticket (Header preferred over Query Param)
            String effectiveTicket = (headerTicket != null && !headerTicket.isBlank()) ? headerTicket.trim() : ticket;
            if (effectiveTicket != null && !effectiveTicket.isBlank()) {
                if (requestedShop == null) {
                    throw new AccessDeniedException("Access denied: Tenant shop identification is strictly required for SSE stream.");
                }
                SseTicketService.SseTicket consumed = sseTicketService.consumeTicket(effectiveTicket, requestedShop, null);
                targetShopId = consumed.shopId();
            } else {
                throw new AccessDeniedException("Access denied: Authentication required for queue stream.");
            }
        }
        return sseEmitterService.createOwnerEmitter(targetShopId);
    }
}
