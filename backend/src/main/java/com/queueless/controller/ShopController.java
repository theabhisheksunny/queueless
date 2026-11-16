package com.queueless.controller;

import com.queueless.dto.ShopSettingsRequest;
import com.queueless.entity.Shop;
import com.queueless.service.AuthenticatedShopResolver;
import com.queueless.service.EntitlementService;
import com.queueless.service.ShopService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/owner/settings")
public class ShopController {

    private final ShopService shopService;
    private final AuthenticatedShopResolver shopResolver;
    private final EntitlementService entitlementService;

    public ShopController(ShopService shopService,
                          AuthenticatedShopResolver shopResolver,
                          EntitlementService entitlementService) {
        this.shopService = shopService;
        this.shopResolver = shopResolver;
        this.entitlementService = entitlementService;
    }

    @GetMapping
    public ResponseEntity<Shop> getSettings(Principal principal) {
        Shop shop = shopResolver.resolveShop(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        return ResponseEntity.ok(shopService.getShop(shop.getId()));
    }

    @PutMapping
    public ResponseEntity<Shop> updateSettings(
            Principal principal,
            @Valid @RequestBody ShopSettingsRequest request) {
        Shop shop = shopResolver.resolveShop(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        Shop updated = shopService.updateShopSettings(shop.getId(), request);
        return ResponseEntity.ok(updated);
    }
}
