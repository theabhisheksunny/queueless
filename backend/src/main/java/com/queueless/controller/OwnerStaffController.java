package com.queueless.controller;

import com.queueless.dto.*;
import com.queueless.entity.PaymentRecord;
import com.queueless.entity.User;
import com.queueless.service.AuthenticatedShopResolver;
import com.queueless.service.SeatService;
import com.queueless.service.StaffService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/owner")
public class OwnerStaffController {

    private final StaffService staffService;
    private final SeatService seatService;
    private final AuthenticatedShopResolver shopResolver;
    private final com.queueless.service.DeviceAuthService deviceAuthService;

    public OwnerStaffController(StaffService staffService,
                                SeatService seatService,
                                AuthenticatedShopResolver shopResolver,
                                com.queueless.service.DeviceAuthService deviceAuthService) {
        this.staffService = staffService;
        this.seatService = seatService;
        this.shopResolver = shopResolver;
        this.deviceAuthService = deviceAuthService;
    }

    @GetMapping("/staff")
    public ResponseEntity<List<StaffSummaryResponse>> listStaff(Principal principal) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        return ResponseEntity.ok(staffService.getStaffList(user.getId()));
    }

    @PostMapping("/staff")
    public ResponseEntity<StaffSummaryResponse> addStaff(
            Principal principal,
            @Valid @RequestBody CreateStaffRequest request) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        return ResponseEntity.ok(staffService.createStaff(user.getId(), request));
    }

    @PutMapping("/staff/{staffId}/branches")
    public ResponseEntity<StaffSummaryResponse> updateStaffBranches(
            Principal principal,
            @PathVariable Long staffId,
            @Valid @RequestBody UpdateStaffBranchesRequest request) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        return ResponseEntity.ok(staffService.updateStaffBranches(user.getId(), staffId, request));
    }

    @PostMapping("/staff/{staffId}/revoke")
    public ResponseEntity<Map<String, String>> revokeStaff(
            Principal principal,
            @PathVariable Long staffId) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        staffService.revokeStaff(user.getId(), staffId);
        return ResponseEntity.ok(Map.of("message", "Staff user revoked successfully. Paid seat has been released for reassignment."));
    }

    @PostMapping("/staff/{staffId}/reset-device")
    public ResponseEntity<Map<String, String>> resetStaffDevice(
            Principal principal,
            @PathVariable Long staffId) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        staffService.resetStaffDevice(user.getId(), staffId);
        return ResponseEntity.ok(Map.of("message", "Staff user active device sessions have been invalidated."));
    }

    @GetMapping("/seats")
    public ResponseEntity<SeatSummaryResponse> getSeatSummary(Principal principal) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        return ResponseEntity.ok(seatService.getSeatSummary(user.getId()));
    }

    @PostMapping("/seats/purchase")
    public ResponseEntity<Map<String, Object>> purchaseSeat(
            Principal principal,
            @Valid @RequestBody PurchaseSeatRequest request) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        PaymentRecord record = seatService.purchaseSeat(user.getId(), request);
        return ResponseEntity.ok(Map.of(
                "message", "Payment submitted for additional user seat. Awaiting administrator verification.",
                "paymentId", record.getId(),
                "status", record.getStatus(),
                "amount", record.getAmount()
        ));
    }

    @GetMapping("/staff/device-replacements")
    public ResponseEntity<List<DeviceReplacementResponse>> listPendingDeviceReplacements(Principal principal) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        return ResponseEntity.ok(deviceAuthService.getPendingRequestsForOwner(user.getId()));
    }

    @PostMapping("/staff/device-replacements/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveDeviceReplacement(
            Principal principal,
            @PathVariable Long id) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        DeviceReplacementResponse resp = deviceAuthService.approveDeviceReplacement(id, user, "Approved by shop owner");
        return ResponseEntity.ok(Map.of(
                "message", "Device replacement request approved. Old device has been revoked and new device is now active.",
                "request", resp
        ));
    }

    @PostMapping("/staff/device-replacements/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectDeviceReplacement(
            Principal principal,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        User user = shopResolver.resolveUser(principal);
        shopResolver.requireOwnerRole(user);
        String reason = body != null && body.get("reason") != null ? body.get("reason").trim() : "Rejected by shop owner";
        DeviceReplacementResponse resp = deviceAuthService.rejectDeviceReplacement(id, user, reason);
        return ResponseEntity.ok(Map.of(
                "message", "Device replacement request rejected.",
                "request", resp
        ));
    }
}
