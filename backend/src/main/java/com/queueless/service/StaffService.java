package com.queueless.service;

import com.queueless.dto.CreateStaffRequest;
import com.queueless.dto.SeatSummaryResponse;
import com.queueless.dto.StaffSummaryResponse;
import com.queueless.dto.UpdateStaffBranchesRequest;
import com.queueless.entity.*;
import com.queueless.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class StaffService {

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);

    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final ShopMembershipRepository membershipRepository;
    private final UserSessionRepository sessionRepository;
    private final AuditLogRepository auditLogRepository;
    private final SeatService seatService;
    private final PasswordEncoder passwordEncoder;
    private final PhoneNormalizationService phoneNormalizer;
    private final DeviceRegistrationRepository deviceRegistrationRepository;
    private final DeviceReplacementRequestRepository replacementRequestRepository;
    private final SubscriptionSeatRepository seatRepository;

    public StaffService(UserRepository userRepository,
                        ShopRepository shopRepository,
                        ShopMembershipRepository membershipRepository,
                        UserSessionRepository sessionRepository,
                        AuditLogRepository auditLogRepository,
                        SeatService seatService,
                        PasswordEncoder passwordEncoder,
                        PhoneNormalizationService phoneNormalizer,
                        DeviceRegistrationRepository deviceRegistrationRepository,
                        DeviceReplacementRequestRepository replacementRequestRepository,
                        SubscriptionSeatRepository seatRepository) {
        this.userRepository = userRepository;
        this.shopRepository = shopRepository;
        this.membershipRepository = membershipRepository;
        this.sessionRepository = sessionRepository;
        this.auditLogRepository = auditLogRepository;
        this.seatService = seatService;
        this.passwordEncoder = passwordEncoder;
        this.phoneNormalizer = phoneNormalizer;
        this.deviceRegistrationRepository = deviceRegistrationRepository;
        this.replacementRequestRepository = replacementRequestRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional(readOnly = true)
    public List<StaffSummaryResponse> getStaffList(Long ownerId) {
        List<User> staffUsers = userRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerId);
        List<StaffSummaryResponse> result = new ArrayList<>();

        for (User staff : staffUsers) {
            List<ShopMembership> memberships = membershipRepository.findAllByUserIdAndStatus(staff.getId(), "ACTIVE");
            List<Shop> branches = memberships.stream().map(ShopMembership::getShop).toList();
            result.add(toStaffSummary(staff, branches));
        }

        return result;
    }

    @Transactional
    public StaffSummaryResponse createStaff(Long ownerId, CreateStaffRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Owner not found: " + ownerId));

        String normalizedPhone = phoneNormalizer.normalize(request.getPhone());

        // Case D: Mobile belongs to the owner
        if (normalizedPhone.equals(owner.getPhone())) {
            throw new IllegalArgumentException("You cannot register the owner's mobile number as a staff operator.");
        }

        // Validate branch IDs belong to owner
        List<Shop> authorizedBranches = new ArrayList<>();
        if (request.getBranchIds() != null && !request.getBranchIds().isEmpty()) {
            for (Long branchId : request.getBranchIds()) {
                Shop branch = shopRepository.findByIdAndOwnerId(branchId, ownerId)
                        .orElseThrow(() -> new IllegalArgumentException("Branch ID " + branchId + " does not belong to owner."));
                authorizedBranches.add(branch);
            }
        } else {
            // Default to owner's primary shop
            Shop primary = shopRepository.findFirstByOwnerIdOrderByIsPrimaryDescCreatedAtAsc(ownerId)
                    .orElseThrow(() -> new IllegalStateException("Owner has no registered shops."));
            authorizedBranches.add(primary);
        }

        Optional<User> existingUserOpt = userRepository.findByPhone(normalizedPhone);
        if (existingUserOpt.isPresent()) {
            User existing = existingUserOpt.get();

            // Case E: Mobile belongs to another owner's account or has another role
            if (existing.getOwner() == null || !existing.getOwner().getId().equals(ownerId)) {
                throw new IllegalArgumentException("This mobile number is already registered on QueueLess with another account.");
            }

            // Case B & C: Existing staff member under the SAME owner
            List<ShopMembership> currentMemberships = membershipRepository.findAllByUserId(existing.getId());
            Set<Long> existingShopIds = new HashSet<>();
            for (ShopMembership m : currentMemberships) {
                if ("ACTIVE".equalsIgnoreCase(m.getStatus())) {
                    existingShopIds.add(m.getShop().getId());
                }
            }

            List<Shop> newBranchesToAdd = new ArrayList<>();
            for (Shop b : authorizedBranches) {
                if (!existingShopIds.contains(b.getId())) {
                    newBranchesToAdd.add(b);
                }
            }

            if (newBranchesToAdd.isEmpty()) {
                // Case C: Already assigned to all selected branches
                throw new IllegalArgumentException("Staff member " + existing.getFullName() + " is already assigned to all selected branches.");
            }

            // Case B: Add only missing branch memberships
            for (Shop b : newBranchesToAdd) {
                membershipRepository.save(new ShopMembership(existing, b));
            }

            // If existing was revoked, re-activate status
            if (existing.isRevoked()) {
                existing.setStatus("ACTIVE");
                userRepository.save(existing);
            }

            // DO NOT create another user, DO NOT reset PIN, DO NOT create another device registration, DO NOT charge another seat!
            auditLogRepository.save(new AuditLog(
                    "ASSIGN_STAFF_BRANCHES",
                    owner.getPhone() != null ? owner.getPhone() : owner.getEmail(),
                    "USER",
                    existing.getId().toString(),
                    "Assigned existing staff " + existing.getFullName() + " to " + newBranchesToAdd.size() + " additional branches without charging another seat",
                    null
            ));

            List<ShopMembership> allMemberships = membershipRepository.findAllByUserIdAndStatus(existing.getId(), "ACTIVE");
            List<Shop> allAssigned = new ArrayList<>();
            for (ShopMembership m : allMemberships) {
                allAssigned.add(m.getShop());
            }
            return toStaffSummary(existing, allAssigned);
        }

        // Case A: New mobile number
        if (request.getPin() == null || !request.getPin().matches("^\\d{6}$")) {
            throw new IllegalArgumentException("Temporary staff PIN must be exactly 6 numeric digits.");
        }

        // Validate seat availability
        SeatSummaryResponse seatSummary = seatService.getSeatSummary(ownerId);
        if (seatSummary.getAvailableSeats() <= 0) {
            throw new IllegalArgumentException("No available user seats. Please purchase an additional user seat (\u20B947/month or \u20B9494/year) before adding more staff.");
        }

        String rawDigits = normalizedPhone.replaceAll("[^0-9]", "");
        String email = "staff_" + rawDigits + "@staff.queueless.in";

        User staff = new User();
        staff.setFullName(request.getFullName().trim());
        staff.setPhone(normalizedPhone);
        staff.setEmail(email);
        staff.setPassword(passwordEncoder.encode(request.getPin()));
        staff.setRole(UserRole.ROLE_STAFF);
        staff.setOwner(owner);
        staff.setStatus("ACTIVE");
        staff.setTemporaryPin(true);
        staff.setCredentialsUpdatedAt(Instant.now());
        staff = userRepository.save(staff);

        // Assign paid seat
        seatService.assignAvailableSeat(ownerId, staff);

        // Create branch memberships
        for (Shop branch : authorizedBranches) {
            ShopMembership membership = new ShopMembership(staff, branch);
            membershipRepository.save(membership);
        }

        auditLogRepository.save(new AuditLog(
                "CREATE_STAFF",
                owner.getPhone() != null ? owner.getPhone() : owner.getEmail(),
                "USER",
                staff.getId().toString(),
                "Created new staff identity " + staff.getFullName() + " (" + staff.getPhone() + ") awaiting first device enrollment",
                null
        ));

        return toStaffSummary(staff, authorizedBranches);
    }

    @Transactional
    public StaffSummaryResponse updateStaffBranches(Long ownerId, Long staffUserId, UpdateStaffBranchesRequest request) {
        User staff = userRepository.findByIdAndOwnerId(staffUserId, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Staff user not found for owner: " + staffUserId));

        List<Shop> newBranches = new ArrayList<>();
        if (request.getBranchIds() != null && !request.getBranchIds().isEmpty()) {
            for (Long branchId : request.getBranchIds()) {
                Shop branch = shopRepository.findByIdAndOwnerId(branchId, ownerId)
                        .orElseThrow(() -> new IllegalArgumentException("Branch ID " + branchId + " does not belong to owner."));
                newBranches.add(branch);
            }
        } else {
            throw new IllegalArgumentException("At least one branch must be assigned to staff member.");
        }

        membershipRepository.deleteAllByUserId(staff.getId());
        for (Shop branch : newBranches) {
            ShopMembership m = new ShopMembership(staff, branch);
            membershipRepository.save(m);
        }

        auditLogRepository.save(new AuditLog(
                "UPDATE_STAFF_BRANCHES",
                staff.getOwner() != null ? staff.getOwner().getPhone() : "owner",
                "USER",
                staff.getId().toString(),
                "Updated branch assignments for " + staff.getFullName(),
                null
        ));

        return toStaffSummary(staff, newBranches);
    }

    @Transactional
    public void revokeStaff(Long ownerId, Long staffUserId) {
        User staff = userRepository.findByIdAndOwnerId(staffUserId, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Staff user not found for owner: " + staffUserId));

        staff.setStatus("REVOKED");
        staff.setActiveSessionId(null);
        staff.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(staff);

        // Revoke memberships
        List<ShopMembership> memberships = membershipRepository.findAllByUserId(staff.getId());
        for (ShopMembership m : memberships) {
            m.setStatus("REVOKED");
            membershipRepository.save(m);
        }

        // Invalidate active sessions
        List<UserSession> sessions = sessionRepository.findAllByUserId(staff.getId());
        for (UserSession s : sessions) {
            s.setStatus("REVOKED");
            s.setRevokedAt(LocalDateTime.now());
            sessionRepository.save(s);
        }

        // Revoke active device registration
        deviceRegistrationRepository.findByUserIdAndEnrollmentStatus(staff.getId(), "ACTIVE").ifPresent(d -> {
            d.setEnrollmentStatus("REVOKED");
            d.setRevokedAt(LocalDateTime.now());
            d.setRevocationReason("Device reset by owner");
            deviceRegistrationRepository.save(d);
        });

        // Release assigned seat
        seatService.unassignSeat(staff);

        // Revoke active device registration
        deviceRegistrationRepository.findByUserIdAndEnrollmentStatus(staff.getId(), "ACTIVE").ifPresent(d -> {
            d.setEnrollmentStatus("REVOKED");
            d.setRevokedAt(LocalDateTime.now());
            d.setRevocationReason("Staff account revoked by owner");
            deviceRegistrationRepository.save(d);
        });

        auditLogRepository.save(new AuditLog(
                "REVOKE_STAFF",
                staff.getOwner() != null ? staff.getOwner().getPhone() : "owner",
                "USER",
                staff.getId().toString(),
                "Revoked staff access for " + staff.getFullName() + ". Seat released for reassignment.",
                null
        ));
    }

    @Transactional
    public void resetStaffDevice(Long ownerId, Long staffUserId) {
        User staff = userRepository.findByIdAndOwnerId(staffUserId, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Staff user not found for owner: " + staffUserId));

        staff.setActiveSessionId(null);
        staff.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(staff);

        List<UserSession> sessions = sessionRepository.findAllByUserId(staff.getId());
        for (UserSession s : sessions) {
            s.setStatus("REVOKED");
            s.setRevokedAt(LocalDateTime.now());
            sessionRepository.save(s);
        }

        // Revoke active device registration
        deviceRegistrationRepository.findByUserIdAndEnrollmentStatus(staff.getId(), "ACTIVE").ifPresent(d -> {
            d.setEnrollmentStatus("REVOKED");
            d.setRevokedAt(LocalDateTime.now());
            d.setRevocationReason("Device reset by owner");
            deviceRegistrationRepository.save(d);
        });

        auditLogRepository.save(new AuditLog(
                "RESET_STAFF_DEVICE",
                staff.getOwner() != null ? staff.getOwner().getPhone() : "owner",
                "USER",
                staff.getId().toString(),
                "Reset device/session for staff " + staff.getFullName() + ". Active sessions invalidated.",
                null
        ));
    }

    private StaffSummaryResponse toStaffSummary(User staff, List<Shop> branches) {
        StaffSummaryResponse resp = new StaffSummaryResponse();
        resp.setId(staff.getId());
        resp.setFullName(staff.getFullName());
        resp.setPhone(staff.getPhone());
        resp.setRole(staff.getRole() != null ? staff.getRole().name() : "ROLE_STAFF");
        resp.setStatus(staff.getStatus());
        resp.setActiveSessionId(staff.getActiveSessionId());
        resp.setLastLoginAt(staff.getLastLoginAt());
        resp.setCreatedAt(staff.getCreatedAt());

        List<Long> branchIds = new ArrayList<>();
        List<String> branchNames = new ArrayList<>();
        for (Shop s : branches) {
            branchIds.add(s.getId());
            branchNames.add(s.getName());
        }
        resp.setAssignedBranchIds(branchIds);
        resp.setAssignedBranchNames(branchNames);

        resp.setTemporaryPin(staff.isTemporaryPin());
        deviceRegistrationRepository.findByUserIdAndEnrollmentStatus(staff.getId(), "ACTIVE").ifPresentOrElse(dev -> {
            resp.setRegisteredDeviceName(dev.getDeviceName());
            resp.setDevicePlatform(dev.getPlatform());
            resp.setDeviceEnrolledAt(dev.getEnrolledAt());
            resp.setDeviceLastActiveAt(dev.getLastActiveAt());
            resp.setDeviceEnrollmentStatus(dev.getEnrollmentStatus());
        }, () -> {
            resp.setDeviceEnrollmentStatus(staff.isTemporaryPin() ? "AWAITING_ENROLLMENT" : "UNENROLLED");
        });

        Optional<DeviceReplacementRequest> pendingRep = replacementRequestRepository
                .findTopByUserIdAndRequestStatusOrderByRequestedAtDesc(staff.getId(), "PENDING");
        if (pendingRep.isPresent() && !pendingRep.get().isExpired()) {
            resp.setHasPendingReplacement(true);
            resp.setPendingReplacementId(pendingRep.get().getId());
        }

        SubscriptionSeat seat = seatRepository.findByAssignedUserIdAndStatus(staff.getId(), SubscriptionStatus.ACTIVE).orElse(null);
        if (seat != null) {
            resp.setSeatPlanType(seat.getPlanType() != null ? seat.getPlanType().name() : null);
            resp.setSeatEndDate(seat.getEndDate());
        }

        return resp;
    }
}
