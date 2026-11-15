package com.queueless.controller;

import com.queueless.dto.*;
import com.queueless.service.CategoryChangeService;
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
    private final CategoryChangeService categoryChangeService;
    private final String adminKey;

    public AdminController(SubscriptionService subscriptionService,
                           CategoryChangeService categoryChangeService,
                           @Value("${app.admin.key:admin-secret-queueless-2026}") String adminKey) {
        this.subscriptionService = subscriptionService;
        this.categoryChangeService = categoryChangeService;
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

    @GetMapping("/category-requests")
    public ResponseEntity<List<AdminCategoryChangeItem>> listCategoryChangeRequests(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        verifyAdminAccess(key);
        return ResponseEntity.ok(categoryChangeService.adminListRequests());
    }

    @PostMapping("/category-requests/{id}/approve")
    public ResponseEntity<Map<String, String>> approveCategoryChangeRequest(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable("id") Long id) {
        verifyAdminAccess(key);
        categoryChangeService.adminApproveRequest(id, "admin");
        return ResponseEntity.ok(Map.of("message", "Category change request approved successfully."));
    }

    @PostMapping("/category-requests/{id}/reject")
    public ResponseEntity<Map<String, String>> rejectCategoryChangeRequest(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable("id") Long id,
            @RequestBody(required = false) AdminCategoryRejectRequest request) {
        verifyAdminAccess(key);
        categoryChangeService.adminRejectRequest(id, request, "admin");
        return ResponseEntity.ok(Map.of("message", "Category change request rejected."));
    }
}
