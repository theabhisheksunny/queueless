package com.queueless.controller;

import com.queueless.dto.*;
import com.queueless.service.CategoryChangeService;
import com.queueless.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final SubscriptionService subscriptionService;
    private final CategoryChangeService categoryChangeService;
    private final com.queueless.service.AuthService authService;
    private final String adminKey;

    public AdminController(SubscriptionService subscriptionService,
                           CategoryChangeService categoryChangeService,
                           com.queueless.service.AuthService authService,
                           @Value("${app.admin.key:admin-secret-queueless-2026}") String adminKey) {
        this.subscriptionService = subscriptionService;
        this.categoryChangeService = categoryChangeService;
        this.authService = authService;
        this.adminKey = adminKey;
    }

    private void verifyAdminAccess(String headerKey) {
        // Can be verified via X-Admin-Key or Spring Security ROLE_ADMIN
        if (headerKey == null || !headerKey.equals(adminKey)) {
            log.warn("AUDIT: Unauthorized admin access attempt detected - keyPresent: {}, timestamp: {}",
                    (headerKey != null && !headerKey.isBlank()), Instant.now());
            throw new org.springframework.security.access.AccessDeniedException("Invalid Admin Key");
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return "null";
        String clean = phone.trim();
        if (clean.length() <= 4) {
            return "****";
        }
        return clean.substring(0, clean.length() - 4).replaceAll(".", "*") + clean.substring(clean.length() - 4);
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

    @PostMapping("/reset-owner-pin")
    public ResponseEntity<Map<String, String>> adminResetPin(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody Map<String, String> body) {
        verifyAdminAccess(key);
        String phone = body != null ? body.get("phone") : null;
        String newPin = body != null ? body.get("newPin") : null;
        try {
            Long targetOwnerId = authService.adminResetPin(phone, newPin);
            log.info("AUDIT: Admin PIN reset SUCCESS - adminIdentity: X-Admin-Key, targetOwnerId: {}, targetPhone: {}, timestamp: {}",
                    targetOwnerId, maskPhone(phone), Instant.now());
            return ResponseEntity.ok(Map.of("message", "Owner PIN successfully reset by administrator."));
        } catch (Exception e) {
            log.warn("AUDIT: Admin PIN reset FAILED - adminIdentity: X-Admin-Key, targetPhone: {}, reason: {}, timestamp: {}",
                    maskPhone(phone), e.getMessage(), Instant.now());
            throw e;
        }
    }
}
