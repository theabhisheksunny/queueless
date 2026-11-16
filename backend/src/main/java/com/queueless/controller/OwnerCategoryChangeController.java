package com.queueless.controller;

import com.queueless.dto.CategoryChangeInitiateRequest;
import com.queueless.dto.CategoryChangePaymentSubmitRequest;
import com.queueless.dto.CategoryChangeStatusResponse;
import com.queueless.entity.Shop;
import com.queueless.entity.User;
import com.queueless.service.AuthenticatedShopResolver;
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
    private final AuthenticatedShopResolver shopResolver;
    private final EntitlementService entitlementService;

    public OwnerCategoryChangeController(CategoryChangeService categoryChangeService,
                                         AuthenticatedShopResolver shopResolver,
                                         EntitlementService entitlementService) {
        this.categoryChangeService = categoryChangeService;
        this.shopResolver = shopResolver;
        this.entitlementService = entitlementService;
    }

    @GetMapping({"", "/status"})
    public ResponseEntity<CategoryChangeStatusResponse> getStatus(Principal principal) {
        Shop shop = shopResolver.resolveShop(principal);
        return ResponseEntity.ok(categoryChangeService.getStatus(shop.getId()));
    }

    @PostMapping("/request")
    public ResponseEntity<CategoryChangeStatusResponse> initiateRequest(
            Principal principal,
            @Valid @RequestBody CategoryChangeInitiateRequest request) {
        User user = shopResolver.resolveUser(principal);
        Shop shop = shopResolver.resolveShop(user);
        entitlementService.requireActiveEntitlement(shop.getId());
        CategoryChangeStatusResponse response = categoryChangeService.initiateRequest(shop.getId(), user.getId(), request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/payment")
    public ResponseEntity<CategoryChangeStatusResponse> submitPayment(
            Principal principal,
            @Valid @RequestBody CategoryChangePaymentSubmitRequest request) {
        User user = shopResolver.resolveUser(principal);
        Shop shop = shopResolver.resolveShop(user);
        entitlementService.requireActiveEntitlement(shop.getId());
        CategoryChangeStatusResponse response = categoryChangeService.submitPayment(shop.getId(), user.getId(), request);
        return ResponseEntity.ok(response);
    }
}
