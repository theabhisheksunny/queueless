package com.queueless.controller;

import com.queueless.dto.AdminSettingRequest;
import com.queueless.dto.AdminShopActivateRequest;
import com.queueless.dto.AdminShopItem;
import com.queueless.dto.SubscriptionStatusResponse;
import com.queueless.service.SubscriptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SubscriptionService subscriptionService;
    private final String adminKey;

    public AdminController(SubscriptionService subscriptionService,
                           @Value("${app.admin.key:admin-secret-queueless-2026}") String adminKey) {
        this.subscriptionService = subscriptionService;
        this.adminKey = adminKey;
    }

    private void verifyAdminAccess(String headerKey) {
        // Can be verified via X-Admin-Key or Spring Security ROLE_ADMIN
        if (headerKey == null || !headerKey.equals(adminKey)) {
            throw new org.springframework.security.access.AccessDeniedException("Invalid Admin Key");
        }
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        verifyAdminAccess(key);
        return ResponseEntity.ok(Map.of(
                "upiId", subscriptionService.getConfiguredUpiId(),
                "upiName", subscriptionService.getConfiguredUpiName(),
                "monthlyPrice", subscriptionService.getConfiguredMonthlyPrice(),
                "yearlyPrice", subscriptionService.getConfiguredYearlyPrice()
        ));
    }

    @PostMapping("/settings")
    public ResponseEntity<Map<String, String>> updateSettings(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody AdminSettingRequest request) {
        verifyAdminAccess(key);
        subscriptionService.adminUpdateSettings(request);
        return ResponseEntity.ok(Map.of("message", "Subscription settings updated successfully."));
    }

    @GetMapping("/shops")
    public ResponseEntity<List<AdminShopItem>> listShops(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        verifyAdminAccess(key);
        return ResponseEntity.ok(subscriptionService.adminGetAllShops());
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, String>> activateShopSubscription(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody AdminShopActivateRequest request) {
        verifyAdminAccess(key);
        subscriptionService.adminActivateShop(request);
        return ResponseEntity.ok(Map.of("message", "Shop subscription activated/extended successfully."));
    }
}
