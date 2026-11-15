package com.queueless.controller;

import com.queueless.dto.ShopSettingsRequest;
import com.queueless.entity.Shop;
import com.queueless.entity.User;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.UserRepository;
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
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final EntitlementService entitlementService;

    public ShopController(ShopService shopService,
                          UserRepository userRepository,
                          ShopRepository shopRepository,
                          EntitlementService entitlementService) {
        this.shopService = shopService;
        this.userRepository = userRepository;
        this.shopRepository = shopRepository;
        this.entitlementService = entitlementService;
    }

    private Shop getShopFromPrincipal(Principal principal) {
        User user = userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + principal.getName()));
        return shopRepository.findByOwnerId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("No shop found for user: " + principal.getName()));
    }

    @GetMapping
    public ResponseEntity<Shop> getSettings(Principal principal) {
        Shop shop = getShopFromPrincipal(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        return ResponseEntity.ok(shopService.getShop(shop.getId()));
    }

    @PutMapping
    public ResponseEntity<Shop> updateSettings(
            Principal principal,
            @Valid @RequestBody ShopSettingsRequest request) {
        Shop shop = getShopFromPrincipal(principal);
        entitlementService.requireActiveEntitlement(shop.getId());
        Shop updated = shopService.updateShopSettings(shop.getId(), request);
        return ResponseEntity.ok(updated);
    }
}
