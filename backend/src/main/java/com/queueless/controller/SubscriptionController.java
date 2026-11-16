package com.queueless.controller;

import com.queueless.dto.SubscriptionPaymentSubmitRequest;
import com.queueless.dto.SubscriptionStatusResponse;
import com.queueless.entity.Shop;
import com.queueless.service.AuthenticatedShopResolver;
import com.queueless.service.SubscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/owner/subscription")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final AuthenticatedShopResolver shopResolver;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  AuthenticatedShopResolver shopResolver) {
        this.subscriptionService = subscriptionService;
        this.shopResolver = shopResolver;
    }

    @GetMapping({"", "/status"})
    public ResponseEntity<SubscriptionStatusResponse> getStatus(Principal principal) {
        Shop shop = shopResolver.resolveShop(principal);
        return ResponseEntity.ok(subscriptionService.getShopSubscriptionStatus(shop.getId()));
    }

    @PostMapping("/payment")
    public ResponseEntity<SubscriptionStatusResponse> submitPayment(
            Principal principal,
            @Valid @RequestBody SubscriptionPaymentSubmitRequest request) {
        Shop shop = shopResolver.resolveShop(principal);
        return ResponseEntity.ok(subscriptionService.submitPayment(shop.getId(), request));
    }
}
