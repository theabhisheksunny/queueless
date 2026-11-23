package com.queueless.controller;

import com.queueless.dto.*;
import com.queueless.entity.PaymentRecord;
import com.queueless.entity.User;
import com.queueless.service.AuthenticatedShopResolver;
import com.queueless.service.BranchService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/owner")
public class OwnerBusinessController {

    private final BranchService branchService;
    private final AuthenticatedShopResolver shopResolver;

    public OwnerBusinessController(BranchService branchService, AuthenticatedShopResolver shopResolver) {
        this.branchService = branchService;
        this.shopResolver = shopResolver;
    }

    @GetMapping("/businesses")
    public ResponseEntity<List<BusinessResponse>> getBusinesses(Principal principal) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        return ResponseEntity.ok(branchService.getBusinessesAndBranches(user.getId()));
    }

    @PostMapping("/businesses")
    public ResponseEntity<BusinessResponse> createBusiness(
            Principal principal,
            @Valid @RequestBody CreateBusinessRequest request) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        return ResponseEntity.ok(branchService.createBusiness(user.getId(), request));
    }

    @GetMapping("/branches")
    public ResponseEntity<List<BranchSummaryResponse>> getBranches(Principal principal) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        return ResponseEntity.ok(branchService.getAllBranches(user.getId()));
    }

    @PostMapping("/branches")
    public ResponseEntity<BranchSummaryResponse> createBranch(
            Principal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody CreateBranchRequest request) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        if ((request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) && idempotencyKeyHeader != null) {
            request.setIdempotencyKey(idempotencyKeyHeader.trim());
        }
        return ResponseEntity.ok(branchService.createBranch(user.getId(), request));
    }

    @PostMapping("/branches/{branchId}/payment")
    public ResponseEntity<Map<String, Object>> submitBranchPayment(
            Principal principal,
            @PathVariable Long branchId,
            @Valid @RequestBody BranchRegistrationPaymentRequest request) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        PaymentRecord record = branchService.submitBranchRegistrationPayment(user.getId(), branchId, request);
        return ResponseEntity.ok(Map.of(
                "message", "Branch registration payment submitted successfully. Awaiting admin verification.",
                "paymentId", record.getId(),
                "status", record.getStatus(),
                "amount", record.getAmount()
        ));
    }

    @PostMapping("/branches/bulk-payment")
    public ResponseEntity<Map<String, Object>> submitBulkBranchPayment(
            Principal principal,
            @Valid @RequestBody BulkBranchPaymentRequest request) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        PaymentRecord record = branchService.submitBulkBranchRegistrationPayment(user.getId(), request);
        return ResponseEntity.ok(Map.of(
                "message", "Bulk branch registration payment submitted successfully for " + record.getBatchCount() + " branches. Awaiting admin verification.",
                "paymentId", record.getId(),
                "status", record.getStatus(),
                "amount", record.getAmount(),
                "batchCount", record.getBatchCount(),
                "batchShopIds", record.getBatchShopIds()
        ));
    }
}
