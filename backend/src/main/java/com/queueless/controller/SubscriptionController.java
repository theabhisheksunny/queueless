package com.queueless.controller;

import com.queueless.dto.SubscriptionPaymentSubmitRequest;
import com.queueless.dto.SubscriptionStatusResponse;
import com.queueless.entity.Shop;
import com.queueless.entity.User;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.UserRepository;
import com.queueless.service.SubscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/owner/subscription")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;

    public SubscriptionController(SubscriptionService subscriptionService,
                                  UserRepository userRepository,
                                  ShopRepository shopRepository) {
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.shopRepository = shopRepository;
    }

    private Shop getShopFromPrincipal(Principal principal) {
        User user = userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + principal.getName()));
        return shopRepository.findByOwnerId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("No shop found for user: " + principal.getName()));
    }

    @GetMapping({"", "/status"})
    public ResponseEntity<SubscriptionStatusResponse> getStatus(Principal principal) {
        Shop shop = getShopFromPrincipal(principal);
        return ResponseEntity.ok(subscriptionService.getShopSubscriptionStatus(shop.getId()));
    }

    @PostMapping("/payment")
    public ResponseEntity<SubscriptionStatusResponse> submitPayment(
            Principal principal,
            @Valid @RequestBody SubscriptionPaymentSubmitRequest request) {
        Shop shop = getShopFromPrincipal(principal);
        return ResponseEntity.ok(subscriptionService.submitPayment(shop.getId(), request));
    }
}
