package com.queueless.service;

import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.exception.AccountLockedException;
import com.queueless.exception.DeviceMismatchException;
import com.queueless.repository.*;
import com.queueless.security.DeviceCryptoUtil;
import com.queueless.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class DeviceAuthService {

    private static final Logger log = LoggerFactory.getLogger(DeviceAuthService.class);

    private final UserRepository userRepository;
    private final DeviceRegistrationRepository deviceRegistrationRepository;
    private final DeviceReplacementRequestRepository replacementRequestRepository;
    private final DeviceAuthChallengeRepository challengeRepository;
    private final UserSessionRepository userSessionRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final PhoneNormalizationService phoneNormalizer;
    private final DeviceCryptoUtil deviceCryptoUtil;
    private final BranchService branchService;
    private final ShopRepository shopRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private Clock clock = Clock.systemUTC();

    public DeviceAuthService(UserRepository userRepository,
                             DeviceRegistrationRepository deviceRegistrationRepository,
                             DeviceReplacementRequestRepository replacementRequestRepository,
                             DeviceAuthChallengeRepository challengeRepository,
                             UserSessionRepository userSessionRepository,
                             AuditLogRepository auditLogRepository,
                             PasswordEncoder passwordEncoder,
                             JwtTokenProvider tokenProvider,
                             PhoneNormalizationService phoneNormalizer,
                             DeviceCryptoUtil deviceCryptoUtil,
                             BranchService branchService,
                             ShopRepository shopRepository) {
        this.userRepository = userRepository;
        this.deviceRegistrationRepository = deviceRegistrationRepository;
        this.replacementRequestRepository = replacementRequestRepository;
        this.challengeRepository = challengeRepository;
        this.userSessionRepository = userSessionRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.phoneNormalizer = phoneNormalizer;
        this.deviceCryptoUtil = deviceCryptoUtil;
        this.branchService = branchService;
        this.shopRepository = shopRepository;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Case 7: First-Time Device Enrollment
     * Staff member enrolls their physical device using their temporary PIN and sets their permanent PIN.
     */
    @Transactional
    public AuthResponse enrollFirstDevice(DeviceEnrollmentRequest request, String clientIp) {
        String normalizedPhone = phoneNormalizer.normalize(request.getPhone());
        User user = userRepository.findByPhone(normalizedPhone)
                .orElseThrow(() -> new BadCredentialsException("Invalid mobile number or credentials."));

        if (user.isRevoked()) {
            throw new BadCredentialsException("Your account access has been revoked by the owner.");
        }

        // Validate temporary PIN
        if (!passwordEncoder.matches(request.getTemporaryPin(), user.getPassword())) {
            throw new BadCredentialsException("Invalid temporary PIN. Please ask your shop owner for your initial 6-digit PIN.");
        }

        if (!user.isTemporaryPin()) {
            throw new IllegalStateException("First-time device enrollment is already complete for this account. Please use normal PIN login.");
        }

        // Validate new permanent PIN
        if (request.getNewPermanentPin() == null || !request.getNewPermanentPin().matches("^\\d{6}$")) {
            throw new IllegalArgumentException("New permanent PIN must be exactly 6 numeric digits.");
        }

        if (!request.getNewPermanentPin().equals(request.getConfirmPermanentPin())) {
            throw new IllegalArgumentException("New permanent PIN and confirmation PIN do not match.");
        }

        if (request.getNewPermanentPin().equals(request.getTemporaryPin())) {
            throw new IllegalArgumentException("Permanent PIN must be different from your temporary PIN for security.");
        }

        // Prevent enrolling a second device without approval
        boolean alreadyHasDevice = deviceRegistrationRepository.existsByUserIdAndEnrollmentStatus(user.getId(), "ACTIVE");
        if (alreadyHasDevice) {
            throw new IllegalStateException("An active device is already enrolled for this account. Credentials alone cannot enroll another device. A device replacement request must be approved.");
        }

        // Register device
        String drid = (request.getDeviceRegistrationId() != null && !request.getDeviceRegistrationId().isBlank())
                ? request.getDeviceRegistrationId().trim()
                : UUID.randomUUID().toString();

        DeviceRegistration reg = new DeviceRegistration(
                user,
                drid,
                request.getPublicKey().trim(),
                request.getDeviceName() != null ? request.getDeviceName().trim() : "Primary Device",
                request.getPlatform() != null ? request.getPlatform().trim() : "Web/PWA"
        );
        deviceRegistrationRepository.save(reg);

        // Update user permanent PIN & status
        user.setPassword(passwordEncoder.encode(request.getNewPermanentPin()));
        user.setTemporaryPin(false);
        user.setStatus("ACTIVE");
        user.setCredentialsUpdatedAt(Instant.now());
        user.setFailedPinAttempts(0);
        user.setPinLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now(clock));
        user.setLastLoginIp(clientIp);

        // Enforce single active session
        String sessionId = UUID.randomUUID().toString();
        userSessionRepository.findByUserIdAndStatus(user.getId(), "ACTIVE").ifPresent(old -> {
            old.setStatus("REVOKED");
            old.setRevokedAt(LocalDateTime.now(clock));
            userSessionRepository.save(old);
        });
        UserSession newSession = new UserSession(user, sessionId, null, clientIp, null);
        userSessionRepository.save(newSession);
        user.setActiveSessionId(sessionId);
        user = userRepository.save(user);

        auditLogRepository.save(new AuditLog(
                "FIRST_DEVICE_ENROLLMENT",
                user.getPhone(),
                "DEVICE",
                reg.getId().toString(),
                "First-time device enrolled: " + reg.getDeviceName() + " (" + reg.getPlatform() + "), permanent PIN configured",
                clientIp
        ));

        String token = tokenProvider.generateTokenForUsername(user.getPhone(), sessionId);
        return buildAuthResponse(user, token, reg);
    }

    /**
     * Case 7b: Request Cryptographic Device Authentication Challenge (Replay Protection)
     * Generates a single-use cryptographically random nonce with short expiry (90 seconds).
     */
    @Transactional
    public DeviceChallengeResponse createChallenge(DeviceChallengeRequest request, String clientIp) {
        String normalizedPhone = phoneNormalizer.normalize(request.getPhone());
        User user = userRepository.findByPhone(normalizedPhone)
                .orElseThrow(() -> new BadCredentialsException("Invalid mobile number or credentials."));

        if (user.isRevoked()) {
            throw new BadCredentialsException("Your account access has been revoked.");
        }

        if (user.isTemporaryPin()) {
            throw new BadCredentialsException("First-time device enrollment required. Please complete first-time enrollment using your temporary PIN.");
        }

        DeviceRegistration activeDevice = deviceRegistrationRepository
                .findByUserIdAndEnrollmentStatus(user.getId(), "ACTIVE")
                .orElse(null);

        if (activeDevice == null) {
            throw new BadCredentialsException("No registered device found for this account. First-time device enrollment required.");
        }

        String presentedDrid = request.getDeviceRegistrationId();
        if (presentedDrid == null || presentedDrid.isBlank() || !presentedDrid.equals(activeDevice.getDeviceRegistrationId())) {
            log.warn("AUDIT: Device mismatch challenge attempt for user {} - registered device: {}, presented: {}",
                    user.getPhone(), activeDevice.getDeviceName(), presentedDrid);
            throw new DeviceMismatchException(
                    "Login blocked: This account is enrolled on another device (" + activeDevice.getDeviceName() + "). " +
                    "To use this device, a device replacement request must be submitted and approved by your shop owner or an administrator."
            );
        }

        // Generate 32 bytes cryptographically secure random challenge
        byte[] nonceBytes = new byte[32];
        secureRandom.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = now.plusSeconds(90);

        challengeRepository.expirePendingChallenges(user.getId(), now);

        DeviceAuthChallenge challenge = new DeviceAuthChallenge(
                user,
                activeDevice.getDeviceRegistrationId(),
                nonce,
                "DEVICE_LOGIN",
                expiresAt
        );
        challengeRepository.save(challenge);

        return new DeviceChallengeResponse(nonce, expiresAt, 90L);
    }

    /**
     * Case 8: Normal Login with Device Proof of Possession
     */
    @Transactional(noRollbackFor = {BadCredentialsException.class, AccountLockedException.class, DeviceMismatchException.class})
    public AuthResponse deviceLogin(DeviceProofLoginRequest request, String clientIp) {
        String normalizedPhone = phoneNormalizer.normalize(request.getPhone());
        User user = userRepository.findByPhone(normalizedPhone)
                .orElseThrow(() -> new BadCredentialsException("Invalid mobile number or PIN."));

        LocalDateTime now = LocalDateTime.now(clock);

        if (user.isRevoked()) {
            throw new BadCredentialsException("Your account access has been revoked.");
        }

        // Lockout check
        if (user.getPinLockedUntil() != null && user.getPinLockedUntil().isAfter(now)) {
            long minutesRemaining = ChronoUnit.MINUTES.between(now, user.getPinLockedUntil()) + 1;
            throw new AccountLockedException("Account temporarily locked due to multiple failed PIN attempts. Please try again after " + minutesRemaining + " minute(s).");
        }

        // Validate PIN
        boolean pinMatches = passwordEncoder.matches(request.getPin(), user.getPassword());
        if (!pinMatches) {
            int attempts = (user.getFailedPinAttempts() != null ? user.getFailedPinAttempts() : 0) + 1;
            user.setFailedPinAttempts(attempts);
            if (attempts >= 5) {
                user.setPinLockedUntil(now.plusMinutes(15));
                userRepository.save(user);
                throw new AccountLockedException("Incorrect PIN. Account locked for 15 minutes due to 5 consecutive failed attempts.");
            } else {
                userRepository.save(user);
                throw new BadCredentialsException("Invalid mobile number or PIN. " + (5 - attempts) + " attempt(s) remaining.");
            }
        }

        // If user is still awaiting first-time device enrollment with temporary PIN
        if (user.isTemporaryPin()) {
            throw new BadCredentialsException("First-time device enrollment required. Please complete first-time enrollment using your temporary PIN.");
        }

        // Find active device registration
        DeviceRegistration activeDevice = deviceRegistrationRepository
                .findByUserIdAndEnrollmentStatus(user.getId(), "ACTIVE")
                .orElse(null);

        // If no active device registered for this account
        if (activeDevice == null) {
            throw new BadCredentialsException("No registered device found for this account. First-time device enrollment required.");
        }

        // CRITICAL SECURITY CHECK: Check if the request comes from the enrolled device
        String presentedDrid = request.getDeviceRegistrationId();
        if (presentedDrid == null || presentedDrid.isBlank() || !presentedDrid.equals(activeDevice.getDeviceRegistrationId())) {
            log.warn("AUDIT: Device mismatch login attempt for user {} - registered device: {}, presented: {}",
                    user.getPhone(), activeDevice.getDeviceName(), presentedDrid);
            throw new DeviceMismatchException(
                    "Login blocked: This account is enrolled on another device (" + activeDevice.getDeviceName() + "). " +
                    "To use this device, a device replacement request must be submitted and approved by your shop owner or an administrator."
            );
        }

        // Verify cryptographic signature (Proof of possession)
        if (request.getSignature() == null || request.getSignature().isBlank()) {
            throw new BadCredentialsException("Device cryptographic verification failed: Signature is required.");
        }

        if (request.getChallenge() != null && !request.getChallenge().isBlank()) {
            DeviceAuthChallenge challenge = challengeRepository.findByChallenge(request.getChallenge())
                    .orElseThrow(() -> new BadCredentialsException("Invalid or unknown authentication challenge."));

            if (!challenge.getUser().getId().equals(user.getId())) {
                throw new BadCredentialsException("Authentication challenge user mismatch.");
            }
            if (!challenge.getDeviceRegistrationId().equals(activeDevice.getDeviceRegistrationId())) {
                throw new BadCredentialsException("Authentication challenge device mismatch.");
            }
            if (challenge.isExpired(now) || !"PENDING".equalsIgnoreCase(challenge.getStatus())) {
                throw new BadCredentialsException("Authentication challenge has expired or has already been consumed. Replay rejected.");
            }

            int rows = challengeRepository.consumeChallengeAtomic(challenge.getId(), now);
            if (rows == 0) {
                throw new BadCredentialsException("Authentication challenge has expired or has already been consumed. Replay rejected.");
            }

            String payload1 = request.getChallenge() + ":" + request.getPhone() + ":" + request.getDeviceRegistrationId();
            String payload2 = request.getChallenge() + ":" + normalizedPhone + ":" + request.getDeviceRegistrationId();
            boolean sigValid = deviceCryptoUtil.verifySignature(activeDevice.getPublicKey(), payload1, request.getSignature())
                    || deviceCryptoUtil.verifySignature(activeDevice.getPublicKey(), payload2, request.getSignature());
            if (!sigValid) {
                log.warn("AUDIT: Cryptographic signature failure for challenge on device {} on user {}", presentedDrid, user.getPhone());
                throw new BadCredentialsException("Device cryptographic verification failed. Untrusted device signature.");
            }
        } else {
            // Timestamp-based signature check
            String payload1 = request.getPhone() + ":" + request.getClientTimestamp() + ":" + request.getDeviceRegistrationId();
            String payload2 = normalizedPhone + ":" + request.getClientTimestamp() + ":" + request.getDeviceRegistrationId();
            boolean sigValid = deviceCryptoUtil.verifySignature(activeDevice.getPublicKey(), payload1, request.getSignature())
                    || deviceCryptoUtil.verifySignature(activeDevice.getPublicKey(), payload2, request.getSignature());
            if (!sigValid) {
                log.warn("AUDIT: Cryptographic signature failure for device {} on user {}", presentedDrid, user.getPhone());
                throw new BadCredentialsException("Device cryptographic verification failed. Untrusted device signature.");
            }
        }

        // Reset failed attempts
        user.setFailedPinAttempts(0);
        user.setPinLockedUntil(null);
        user.setLastLoginAt(now);
        user.setLastLoginIp(clientIp);

        // Update device last active
        activeDevice.setLastActiveAt(now);
        deviceRegistrationRepository.save(activeDevice);

        // Enforce single active device session
        String sessionId = UUID.randomUUID().toString();
        userSessionRepository.findByUserIdAndStatus(user.getId(), "ACTIVE").ifPresent(old -> {
            old.setStatus("REVOKED");
            old.setRevokedAt(now);
            userSessionRepository.save(old);
        });
        UserSession newSession = new UserSession(user, sessionId, request.getDeviceFingerprint(), clientIp, null);
        userSessionRepository.save(newSession);
        user.setActiveSessionId(sessionId);
        user = userRepository.save(user);

        auditLogRepository.save(new AuditLog(
                "DEVICE_LOGIN",
                user.getPhone(),
                "USER",
                user.getId().toString(),
                "Successful login on enrolled device " + activeDevice.getDeviceName() + " (" + activeDevice.getDeviceRegistrationId() + ")",
                clientIp
        ));

        String token = tokenProvider.generateTokenForUsername(user.getPhone(), sessionId);
        return buildAuthResponse(user, token, activeDevice);
    }

    /**
     * Case 9: Submit Device Replacement Request from a new device
     */
    @Transactional
    public DeviceReplacementResponse requestDeviceReplacement(DeviceReplacementSubmitRequest request, String clientIp) {
        String normalizedPhone = phoneNormalizer.normalize(request.getPhone());
        User user = userRepository.findByPhone(normalizedPhone)
                .orElseThrow(() -> new BadCredentialsException("Invalid mobile number or PIN."));

        if (!passwordEncoder.matches(request.getPin(), user.getPassword())) {
            throw new BadCredentialsException("Invalid mobile number or PIN.");
        }

        if (user.isRevoked()) {
            throw new BadCredentialsException("Your account access has been revoked.");
        }

        DeviceRegistration activeDevice = deviceRegistrationRepository
                .findByUserIdAndEnrollmentStatus(user.getId(), "ACTIVE")
                .orElse(null);

        // Check if there's already a pending replacement request
        Optional<DeviceReplacementRequest> existingPending = replacementRequestRepository
                .findTopByUserIdAndRequestStatusOrderByRequestedAtDesc(user.getId(), "PENDING");

        if (existingPending.isPresent() && !existingPending.get().isExpired()) {
            throw new IllegalStateException("A device replacement request is already pending for this account. Please wait for your owner or admin to review it.");
        }

        String reqDrid = (request.getRequestedDeviceRegistrationId() != null && !request.getRequestedDeviceRegistrationId().isBlank())
                ? request.getRequestedDeviceRegistrationId().trim()
                : UUID.randomUUID().toString();

        DeviceReplacementRequest rep = new DeviceReplacementRequest(
                user,
                activeDevice,
                reqDrid,
                request.getRequestedDeviceName() != null ? request.getRequestedDeviceName().trim() : "New Device",
                request.getRequestedDevicePlatform() != null ? request.getRequestedDevicePlatform().trim() : "Web/PWA",
                request.getRequestedPublicKey().trim()
        );
        rep = replacementRequestRepository.save(rep);

        auditLogRepository.save(new AuditLog(
                "REQUEST_DEVICE_REPLACEMENT",
                user.getPhone(),
                "DEVICE_REPLACEMENT",
                rep.getId().toString(),
                "Submitted device replacement request for " + user.getPhone() + " (" + rep.getRequestedDeviceName() + ", drid=" + reqDrid + ")",
                clientIp
        ));

        return toReplacementResponse(rep);
    }

    /**
     * Case 9: Approve Device Replacement (Owner OR authorized Admin)
     */
    @Transactional
    public DeviceReplacementResponse approveDeviceReplacement(Long requestId, User approver, String reason) {
        DeviceReplacementRequest rep = replacementRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Device replacement request not found: " + requestId));

        if (!"PENDING".equalsIgnoreCase(rep.getRequestStatus())) {
            throw new IllegalStateException("Device replacement request is already " + rep.getRequestStatus());
        }

        if (rep.isExpired()) {
            rep.setRequestStatus("EXPIRED");
            replacementRequestRepository.save(rep);
            throw new IllegalStateException("Device replacement request has expired. Please submit a new request from the device.");
        }

        User staffUser = rep.getUser();

        // Security check: staff cannot approve their own replacement
        if (staffUser.getId().equals(approver.getId())) {
            throw new AccessDeniedException("Staff members cannot approve their own device replacement request.");
        }

        // Security check: an owner can only approve their own staff
        boolean isAdmin = approver.getRole() == UserRole.ROLE_ADMIN || approver.getRole() == UserRole.ROLE_SUPER_ADMIN;
        boolean isOwner = approver.getRole() == UserRole.ROLE_OWNER;

        if (isOwner) {
            if (staffUser.getOwner() == null || !staffUser.getOwner().getId().equals(approver.getId())) {
                throw new AccessDeniedException("You do not own this staff account. Approval denied.");
            }
        } else if (!isAdmin) {
            throw new AccessDeniedException("Unauthorized: Only shop owners or administrators can approve device replacement.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        final Long reqId = rep.getId();
        String reviewer = approver.getPhone() != null ? approver.getPhone() : approver.getEmail();
        String roleStr = isAdmin ? "ADMIN" : "OWNER";

        int updated = replacementRequestRepository.approveAtomic(rep.getId(), reviewer, roleStr, now);
        if (updated == 0) {
            throw new IllegalStateException("Device replacement request is already processed or expired.");
        }

        // 1. Atomically revoke old active device registration
        deviceRegistrationRepository.findByUserIdAndEnrollmentStatus(staffUser.getId(), "ACTIVE").ifPresent(old -> {
            old.setEnrollmentStatus("REVOKED");
            old.setRevokedAt(now);
            old.setRevocationReason("Replaced by new device via approved request #" + reqId);
            deviceRegistrationRepository.save(old);
        });

        // 2. Invalidate all active sessions on old device
        staffUser.setActiveSessionId(null);
        staffUser.setCredentialsUpdatedAt(Instant.now());
        userSessionRepository.findByUserIdAndStatus(staffUser.getId(), "ACTIVE").ifPresent(sess -> {
            sess.setStatus("REVOKED");
            sess.setRevokedAt(now);
            userSessionRepository.save(sess);
        });
        userRepository.save(staffUser);

        // 3. Register the new approved device using exact bound deviceRegistrationId and publicKey
        String newDrid = (rep.getRequestedDeviceRegistrationId() != null && !rep.getRequestedDeviceRegistrationId().isBlank())
                ? rep.getRequestedDeviceRegistrationId()
                : UUID.randomUUID().toString();

        DeviceRegistration newReg = new DeviceRegistration(
                staffUser,
                newDrid,
                rep.getRequestedPublicKey(),
                rep.getRequestedDeviceName(),
                rep.getRequestedDevicePlatform()
        );
        deviceRegistrationRepository.save(newReg);

        rep.setRequestStatus("APPROVED");
        rep.setApprovalRole(roleStr);
        rep.setReviewedBy(reviewer);
        rep.setReviewedAt(now);

        auditLogRepository.save(new AuditLog(
                "APPROVE_DEVICE_REPLACEMENT",
                rep.getReviewedBy(),
                "DEVICE_REPLACEMENT",
                rep.getId().toString(),
                "Approved device replacement for " + staffUser.getPhone() + ". New device enrolled: " + newReg.getDeviceName(),
                null
        ));

        return toReplacementResponse(rep);
    }

    /**
     * Case 9b: Complete Device Replacement Enrollment (Called from new approved device)
     */
    @Transactional
    public AuthResponse completeDeviceReplacement(DeviceReplacementCompleteRequest request, String clientIp) {
        String normalizedPhone = phoneNormalizer.normalize(request.getPhone());
        User user = userRepository.findByPhone(normalizedPhone)
                .orElseThrow(() -> new BadCredentialsException("Invalid mobile number or credentials."));

        if (user.isRevoked()) {
            throw new BadCredentialsException("Your account access has been revoked.");
        }

        if (!passwordEncoder.matches(request.getPin(), user.getPassword())) {
            throw new BadCredentialsException("Invalid mobile number or PIN.");
        }

        DeviceReplacementRequest rep = replacementRequestRepository
                .findTopByUserIdAndRequestStatusOrderByReviewedAtDesc(user.getId(), "APPROVED")
                .orElseThrow(() -> new IllegalStateException("No approved device replacement request found for this account."));

        if (rep.isExpired()) {
            rep.setRequestStatus("EXPIRED");
            replacementRequestRepository.save(rep);
            throw new IllegalStateException("Device replacement request has expired. Please submit a new request.");
        }

        // Verify exact binding
        if (rep.getRequestedDeviceRegistrationId() != null && !rep.getRequestedDeviceRegistrationId().isBlank()) {
            if (!rep.getRequestedDeviceRegistrationId().equals(request.getDeviceRegistrationId())) {
                throw new AccessDeniedException("Device mismatch: Presented device registration ID does not match the approved replacement request.");
            }
        }

        if (!rep.getRequestedPublicKey().trim().equals(request.getPublicKey().trim())) {
            throw new AccessDeniedException("Device mismatch: Presented cryptographic public key does not match the approved replacement request.");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int rows = replacementRequestRepository.completeAtomic(rep.getId(), now);
        if (rows == 0) {
            throw new IllegalStateException("Device replacement request has already been completed or expired.");
        }

        // Ensure device registration is active
        final User finalUser = user;
        DeviceRegistration activeDevice = deviceRegistrationRepository
                .findByDeviceRegistrationId(request.getDeviceRegistrationId())
                .orElseGet(() -> {
                    DeviceRegistration reg = new DeviceRegistration(
                            finalUser,
                            request.getDeviceRegistrationId(),
                            rep.getRequestedPublicKey(),
                            rep.getRequestedDeviceName(),
                            rep.getRequestedDevicePlatform()
                    );
                    return deviceRegistrationRepository.save(reg);
                });

        activeDevice.setEnrollmentStatus("ACTIVE");
        activeDevice.setLastActiveAt(now);
        deviceRegistrationRepository.save(activeDevice);

        user.setFailedPinAttempts(0);
        user.setPinLockedUntil(null);
        user.setLastLoginAt(now);
        user.setLastLoginIp(clientIp);

        String sessionId = UUID.randomUUID().toString();
        userSessionRepository.findByUserIdAndStatus(user.getId(), "ACTIVE").ifPresent(old -> {
            old.setStatus("REVOKED");
            old.setRevokedAt(now);
            userSessionRepository.save(old);
        });
        UserSession newSession = new UserSession(user, sessionId, null, clientIp, null);
        userSessionRepository.save(newSession);
        user.setActiveSessionId(sessionId);
        user = userRepository.save(user);

        auditLogRepository.save(new AuditLog(
                "COMPLETE_DEVICE_REPLACEMENT",
                user.getPhone(),
                "DEVICE",
                activeDevice.getId().toString(),
                "Completed device replacement enrollment on " + activeDevice.getDeviceName(),
                clientIp
        ));

        String token = tokenProvider.generateTokenForUsername(user.getPhone(), sessionId);
        return buildAuthResponse(user, token, activeDevice);
    }

    /**
     * User Logout: Invalidate active user session while preserving device enrollment.
     */
    @Transactional
    public void logout(String username, String sessionId) {
        if (username == null || username.isBlank()) return;
        userRepository.findByIdentifier(username).ifPresent(user -> {
            user.setActiveSessionId(null);
            user.setCredentialsUpdatedAt(Instant.now());
            userRepository.save(user);

            if (sessionId != null) {
                userSessionRepository.findByUserIdAndStatus(user.getId(), "ACTIVE").ifPresent(sess -> {
                    if (sess.getSessionId().equals(sessionId)) {
                        sess.setStatus("REVOKED");
                        sess.setRevokedAt(LocalDateTime.now(clock));
                        userSessionRepository.save(sess);
                    }
                });
            }

            auditLogRepository.save(new AuditLog(
                    "USER_LOGOUT",
                    user.getPhone() != null ? user.getPhone() : user.getEmail(),
                    "USER",
                    user.getId().toString(),
                    "User logged out. Active session invalidated.",
                    null
            ));
        });
    }

    /**
     * Case 9: Reject Device Replacement
     */
    @Transactional
    public DeviceReplacementResponse rejectDeviceReplacement(Long requestId, User approver, String reason) {
        DeviceReplacementRequest rep = replacementRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Device replacement request not found: " + requestId));

        if (!"PENDING".equalsIgnoreCase(rep.getRequestStatus())) {
            throw new IllegalStateException("Device replacement request is already " + rep.getRequestStatus());
        }

        User staffUser = rep.getUser();
        boolean isAdmin = approver.getRole() == UserRole.ROLE_ADMIN || approver.getRole() == UserRole.ROLE_SUPER_ADMIN;
        boolean isOwner = approver.getRole() == UserRole.ROLE_OWNER;

        if (isOwner) {
            if (staffUser.getOwner() == null || !staffUser.getOwner().getId().equals(approver.getId())) {
                throw new AccessDeniedException("You do not own this staff account. Rejection denied.");
            }
        } else if (!isAdmin) {
            throw new AccessDeniedException("Unauthorized: Only shop owners or administrators can reject device replacement.");
        }

        rep.setRequestStatus("REJECTED");
        rep.setApprovalRole(isAdmin ? "ADMIN" : "OWNER");
        rep.setReviewedBy(approver.getPhone() != null ? approver.getPhone() : approver.getEmail());
        rep.setReviewedAt(LocalDateTime.now(clock));
        rep.setRejectionReason(reason != null && !reason.isBlank() ? reason.trim() : "Rejected by " + (isAdmin ? "administrator" : "shop owner"));
        rep = replacementRequestRepository.save(rep);

        auditLogRepository.save(new AuditLog(
                "REJECT_DEVICE_REPLACEMENT",
                rep.getReviewedBy(),
                "DEVICE_REPLACEMENT",
                rep.getId().toString(),
                "Rejected device replacement for " + staffUser.getPhone() + ": " + rep.getRejectionReason(),
                null
        ));

        return toReplacementResponse(rep);
    }

    @Transactional(readOnly = true)
    public List<DeviceReplacementResponse> getPendingRequestsForOwner(Long ownerId) {
        List<DeviceReplacementRequest> list = replacementRequestRepository.findAllByOwnerIdAndStatus(ownerId, "PENDING");
        List<DeviceReplacementResponse> result = new ArrayList<>();
        for (DeviceReplacementRequest r : list) {
            result.add(toReplacementResponse(r));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<DeviceReplacementResponse> getAllRequestsForAdmin() {
        List<DeviceReplacementRequest> list = replacementRequestRepository.findAllByRequestStatusOrderByRequestedAtDesc("PENDING");
        List<DeviceReplacementResponse> result = new ArrayList<>();
        for (DeviceReplacementRequest r : list) {
            result.add(toReplacementResponse(r));
        }
        return result;
    }

    public DeviceReplacementResponse toReplacementResponse(DeviceReplacementRequest r) {
        DeviceReplacementResponse resp = new DeviceReplacementResponse();
        resp.setId(r.getId());
        if (r.getUser() != null) {
            resp.setUserId(r.getUser().getId());
            resp.setUserName(r.getUser().getFullName());
            resp.setUserPhone(r.getUser().getPhone());
        }
        if (r.getOldDevice() != null) {
            resp.setOldDeviceId(r.getOldDevice().getId());
            resp.setOldDeviceName(r.getOldDevice().getDeviceName());
        }
        resp.setRequestedDeviceName(r.getRequestedDeviceName());
        resp.setRequestedDevicePlatform(r.getRequestedDevicePlatform());
        resp.setRequestedDeviceRegistrationId(r.getRequestedDeviceRegistrationId());
        resp.setRequestStatus(r.getRequestStatus());
        resp.setRequestedAt(r.getRequestedAt());
        resp.setExpiresAt(r.getExpiresAt());
        resp.setReviewedBy(r.getReviewedBy());
        resp.setApprovalRole(r.getApprovalRole());
        resp.setReviewedAt(r.getReviewedAt());
        resp.setRejectionReason(r.getRejectionReason());
        return resp;
    }

    private AuthResponse buildAuthResponse(User user, String token, DeviceRegistration reg) {
        Shop primaryShop = null;
        if (user.getRole() == UserRole.ROLE_OWNER) {
            primaryShop = shopRepository.findFirstByOwnerIdOrderByIsPrimaryDescCreatedAtAsc(user.getId()).orElse(null);
        } else if (user.getRole() == UserRole.ROLE_STAFF && user.getOwner() != null) {
            primaryShop = shopRepository.findFirstByOwnerIdOrderByIsPrimaryDescCreatedAtAsc(user.getOwner().getId()).orElse(null);
        }

        AuthResponse resp = new AuthResponse(
                token,
                user.getEmail(),
                user.getPhone(),
                user.getFullName(),
                user.getRole(),
                primaryShop != null ? primaryShop.getId() : 0L,
                primaryShop != null ? primaryShop.getName() : "QueueLess",
                primaryShop != null ? primaryShop.getCategory() : "General",
                true,
                7
        );

        if (user.getRole() == UserRole.ROLE_OWNER) {
            resp.setBusinesses(branchService.getBusinessesAndBranches(user.getId()));
            resp.setBranches(branchService.getAllBranches(user.getId()));
        } else if (user.getRole() == UserRole.ROLE_STAFF && user.getOwner() != null) {
            resp.setBranches(branchService.getAllBranches(user.getOwner().getId()));
        }

        return resp;
    }
}
