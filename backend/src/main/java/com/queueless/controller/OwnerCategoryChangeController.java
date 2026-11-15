package com.queueless.controller;

import com.queueless.dto.CategoryChangeInitiateRequest;
import com.queueless.dto.CategoryChangePaymentSubmitRequest;
import com.queueless.dto.CategoryChangeStatusResponse;
import com.queueless.entity.Shop;
import com.queueless.entity.User;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.UserRepository;
import com.queueless.service.CategoryChangeService;
import com.queueless.service.EntitlementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/owner/category-change")
public class OwnerCategoryChangeController {

    private final CategoryChangeService categoryChangeService;
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final EntitlementService entitlementService;

    public OwnerCategoryChangeController(CategoryChangeService categoryChangeService,
                                         UserRepository userRepository,
                                         ShopRepository shopRepository,
                                         EntitlementService entitlementService) {
        this.categoryChangeService = categoryChangeService;
        this.userRepository = userRepository;
        this.shopRepository = shopRepository;
        this.entitlementService = entitlementService;
    }

    private User getAuthenticatedUser(Principal principal) {
        return userRepository.findByIdentifier(principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + principal.getName()));
    }

    private Shop getShopForUser(User user) {
        return shopRepository.findByOwnerId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("No shop found for user: " + user.getEmail()));
    }

    @GetMapping({"", "/status"})
    public ResponseEntity<CategoryChangeStatusResponse> getStatus(Principal principal) {
        User user = getAuthenticatedUser(principal);
        Shop shop = getShopForUser(user);
        return ResponseEntity.ok(categoryChangeService.getStatus(shop.getId()));
    }

    @PostMapping("/request")
    public ResponseEntity<CategoryChangeStatusResponse> initiateRequest(
            Principal principal,
            @Valid @RequestBody CategoryChangeInitiateRequest request) {
        User user = getAuthenticatedUser(principal);
        Shop shop = getShopForUser(user);
        entitlementService.requireActiveEntitlement(shop.getId());
        CategoryChangeStatusResponse response = categoryChangeService.initiateRequest(shop.getId(), user.getId(), request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/payment")
    public ResponseEntity<CategoryChangeStatusResponse> submitPayment(
            Principal principal,
            @Valid @RequestBody CategoryChangePaymentSubmitRequest request) {
        User user = getAuthenticatedUser(principal);
        Shop shop = getShopForUser(user);
        entitlementService.requireActiveEntitlement(shop.getId());
        CategoryChangeStatusResponse response = categoryChangeService.submitPayment(shop.getId(), user.getId(), request);
        return ResponseEntity.ok(response);
    }
}
