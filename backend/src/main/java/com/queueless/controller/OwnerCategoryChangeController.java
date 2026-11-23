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
    public ResponseEntity<CategoryChangeStatusResponse> getStatus(
            Principal principal,
            @RequestParam(value = "shopId", required = false) Long paramShopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId) {
        Long target = paramShopId != null ? paramShopId : headerShopId;
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        Shop shop = shopResolver.resolveShop(user, target);
        return ResponseEntity.ok(categoryChangeService.getStatus(shop.getId()));
    }

    @PostMapping("/request")
    public ResponseEntity<CategoryChangeStatusResponse> initiateRequest(
            Principal principal,
            @RequestParam(value = "shopId", required = false) Long paramShopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId,
            @Valid @RequestBody CategoryChangeInitiateRequest request) {
        Long target = paramShopId != null ? paramShopId : headerShopId;
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        Shop shop = shopResolver.resolveShop(user, target);
        entitlementService.requireActiveEntitlement(shop.getId());
        CategoryChangeStatusResponse response = categoryChangeService.initiateRequest(shop.getId(), user.getId(), request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/payment")
    public ResponseEntity<CategoryChangeStatusResponse> submitPayment(
            Principal principal,
            @RequestParam(value = "shopId", required = false) Long paramShopId,
            @RequestHeader(value = "X-Shop-Id", required = false) Long headerShopId,
            @Valid @RequestBody CategoryChangePaymentSubmitRequest request) {
        Long target = paramShopId != null ? paramShopId : headerShopId;
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        Shop shop = shopResolver.resolveShop(user, target);
        entitlementService.requireActiveEntitlement(shop.getId());
        CategoryChangeStatusResponse response = categoryChangeService.submitPayment(shop.getId(), user.getId(), request);
        return ResponseEntity.ok(response);
    }
}
