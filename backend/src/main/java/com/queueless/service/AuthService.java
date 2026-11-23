package com.queueless.service;

import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.exception.AccountLockedException;
import com.queueless.exception.DeviceMismatchException;
import com.queueless.exception.TrialAlreadyConsumedException;
import com.queueless.repository.*;
import com.queueless.security.JwtTokenProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TrialEligibilityLedgerRepository trialEligibilityLedgerRepository;
    private final QueueSessionRepository sessionRepository;
    private final QueueCustomerRepository customerRepository;
    private final PhoneNormalizationService phoneNormalizationService;
    private final EntitlementService entitlementService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final BusinessRepository businessRepository;
    private final ShopMembershipRepository membershipRepository;
    private final SubscriptionSeatRepository seatRepository;
    private final UserSessionRepository userSessionRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final BranchService branchService;
    private final DeviceRegistrationRepository deviceRegistrationRepository;
    private Clock clock;

    public AuthService(UserRepository userRepository,
                       ShopRepository shopRepository,
                       SubscriptionRepository subscriptionRepository,
                       TrialEligibilityLedgerRepository trialEligibilityLedgerRepository,
                       QueueSessionRepository sessionRepository,
                       QueueCustomerRepository customerRepository,
                       PhoneNormalizationService phoneNormalizationService,
                       EntitlementService entitlementService,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtTokenProvider tokenProvider,
                       BusinessRepository businessRepository,
                       ShopMembershipRepository membershipRepository,
                       SubscriptionSeatRepository seatRepository,
                       UserSessionRepository userSessionRepository,
                       PaymentRecordRepository paymentRecordRepository,
                       BranchService branchService,
                       DeviceRegistrationRepository deviceRegistrationRepository,
                       Clock clock) {
        this.userRepository = userRepository;
        this.shopRepository = shopRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.trialEligibilityLedgerRepository = trialEligibilityLedgerRepository;
        this.sessionRepository = sessionRepository;
        this.customerRepository = customerRepository;
        this.phoneNormalizationService = phoneNormalizationService;
        this.entitlementService = entitlementService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.businessRepository = businessRepository;
        this.membershipRepository = membershipRepository;
        this.seatRepository = seatRepository;
        this.userSessionRepository = userSessionRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.branchService = branchService;
        this.deviceRegistrationRepository = deviceRegistrationRepository;
        this.clock = clock;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
        this.entitlementService.setClock(clock);
        this.tokenProvider.setClock(clock);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (request.getPhone() == null || request.getPhone().isBlank()) {
            throw new IllegalArgumentException("Mobile number is required.");
        }

        // Normalize Indian mobile number
        String normalizedPhone = phoneNormalizationService.normalize(request.getPhone());

        // Check if an account already exists with this phone number
        if (userRepository.existsByPhone(normalizedPhone)) {
            throw new IllegalArgumentException("An account with this mobile number already exists. Please log in.");
        }

        // Check persistent trial eligibility ledger (survives account deletion)
        if (trialEligibilityLedgerRepository.existsByNormalizedPhone(normalizedPhone)) {
            throw new TrialAlreadyConsumedException(
                    "A 7-day free trial has already been claimed for this mobile number. This identity is not eligible for another free trial. Please log in or contact support."
            );
        }

        // Validate 6-digit Secret PIN
        String credential;
        if (request.getPin() != null && !request.getPin().isBlank()) {
            String pin = request.getPin().trim();
            if (!pin.matches("^\\d{6}$")) {
                throw new IllegalArgumentException("PIN must be exactly 6 digits.");
            }
            if (request.getConfirmPin() == null || !pin.equals(request.getConfirmPin().trim())) {
                throw new IllegalArgumentException("PIN and Confirm PIN do not match.");
            }
            credential = pin;
        } else if (request.getPassword() != null && !request.getPassword().isBlank()) {
            credential = request.getPassword().trim();
        } else {
            throw new IllegalArgumentException("A 6-digit PIN is required.");
        }

        // Resolve email (optional for non-technical users; generated fallback if omitted)
        String email;
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            email = request.getEmail().trim().toLowerCase();
        } else {
            String rawPhoneDigits = normalizedPhone.replaceAll("[^0-9]", "");
            email = rawPhoneDigits + "@owner.queueless.local";
        }

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }

        // Create User with BCrypt hashed credential (never stored plaintext)
        User user = new User(
                email,
                passwordEncoder.encode(credential),
                request.getFullName() != null ? request.getFullName().trim() : "Shop Owner",
                normalizedPhone,
                UserRole.ROLE_OWNER
        );
        LocalDateTime nowUtc = LocalDateTime.now(clock);
        user.setCredentialsUpdatedAt(clock.instant());
        user.setLastLoginAt(nowUtc);
        user.setLastDeviceFingerprint(request.getDeviceFingerprint());
        user.setFailedPinAttempts(0);
        String sessionId = UUID.randomUUID().toString();
        user.setActiveSessionId(sessionId);
        user = userRepository.save(user);

        UserSession userSession = new UserSession(user, sessionId, request.getDeviceFingerprint(), null, null);
        userSessionRepository.save(userSession);

        // Create default Business for owner
        String businessName = request.getShopName() != null ? request.getShopName().trim() : "My Business";
        Business business = new Business(user, businessName);
        business = businessRepository.save(business);

        // Create primary Shop
        Shop shop = new Shop(
                user,
                request.getShopName() != null ? request.getShopName().trim() : "My Shop",
                request.getCategory() != null ? request.getCategory().trim() : "General",
                request.getAvgServiceDurationMinutes() != null ? request.getAvgServiceDurationMinutes() : 15
        );
        shop.setBusiness(business);
        shop.setIsPrimary(true);
        shop.setRegistrationStatus("REGISTERED");
        shop.setRegistrationFeePaid(BigDecimal.ZERO);
        shop = shopRepository.save(shop);

        // Grant exactly 7 calendar days trial
        LocalDate today = LocalDate.now(clock);
        LocalDate expiryDate = today.plusDays(7);
        LocalDateTime trialEndsAt = nowUtc.plusDays(7);

        Subscription trial = new Subscription(
                user,
                shop,
                SubscriptionPlan.TRIAL,
                SubscriptionStatus.ACTIVE,
                today,
                expiryDate,
                BigDecimal.ZERO
        );
        trial.setNotes("Complimentary 7-day initial trial");
        trial.setTrialStartedAt(nowUtc);
        trial.setTrialEndsAt(trialEndsAt);
        trial.setTrialConsumedAt(nowUtc);
        subscriptionRepository.save(trial);

        // Persist durable Trial Eligibility Ledger entry (cannot be erased by account deletion)
        TrialEligibilityLedger ledger = new TrialEligibilityLedger();
        ledger.setNormalizedPhone(normalizedPhone);
        ledger.setPhoneHmac(phoneNormalizationService.computeHmac(normalizedPhone));
        ledger.setTrialStartedAt(nowUtc);
        ledger.setTrialEndsAt(trialEndsAt);
        ledger.setTrialConsumedAt(nowUtc);
        ledger.setOwnerId(user.getId());
        ledger.setShopId(shop.getId());
        ledger.setOwnerEmail(user.getEmail());
        ledger.setRegistrationStatus("ACTIVE");
        ledger.setDeviceFingerprint(request.getDeviceFingerprint());
        trialEligibilityLedgerRepository.save(ledger);

        String tokenSubject = user.getPhone() != null ? user.getPhone() : user.getEmail();
        String token = tokenProvider.generateTokenForUsername(tokenSubject, sessionId);

        AuthResponse resp = new AuthResponse(
                token,
                user.getEmail(),
                user.getPhone(),
                user.getFullName(),
                user.getRole(),
                shop.getId(),
                shop.getName(),
                shop.getCategory(),
                true,
                7
        );
        resp.setBusinesses(branchService.getBusinessesAndBranches(user.getId()));
        resp.setBranches(branchService.getAllBranches(user.getId()));
        return resp;
    }

    /**
     * Mobile + 6-digit PIN login with brute-force lockout (5 attempts -> 15 min lock).
     * No SMS OTP on routine login.
     */
    @Transactional(noRollbackFor = {BadCredentialsException.class, AccountLockedException.class})
    public AuthResponse pinLogin(PinLoginRequest request, String clientIp) {
        if (request.getPhone() == null || request.getPhone().isBlank()) {
            throw new BadCredentialsException("Mobile number is required.");
        }
        if (request.getPin() == null || !request.getPin().matches("^\\d{6}$")) {
            throw new BadCredentialsException("PIN must be exactly 6 digits.");
        }

        String normalizedPhone = phoneNormalizationService.normalize(request.getPhone());
        Optional<User> userOpt = userRepository.findByPhone(normalizedPhone);
        if (userOpt.isEmpty() && normalizedPhone.startsWith("+91")) {
            userOpt = userRepository.findByPhone(normalizedPhone.substring(3));
            userOpt.ifPresent(u -> {
                u.setPhone(normalizedPhone);
                userRepository.save(u);
            });
        }
        if (userOpt.isEmpty()) {
            // Anti-enumeration defense: perform dummy BCrypt comparison and progressive delay
            passwordEncoder.matches("000000", "$2a$10$7EqJtq98hPqEX7fNZaFWoO.fvyuW.zY3qJ7gQ5n4Vf8kL6YwzO7Gm");
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ignored) {}
            throw new BadCredentialsException("Invalid mobile number or PIN.");
        }

        User user = userOpt.get();
        LocalDateTime now = LocalDateTime.now(clock);

        if (user.isRevoked()) {
            throw new BadCredentialsException("Your account access has been revoked by the owner.");
        }

        if (user.getRole() == UserRole.ROLE_STAFF) {
            SubscriptionSeat seat = seatRepository.findByAssignedUserIdAndStatus(user.getId(), SubscriptionStatus.ACTIVE).orElse(null);
            LocalDate today = LocalDate.now(clock);
            if (seat != null) {
                if (seat.getEndDate() == null || today.isAfter(seat.getEndDate())) {
                    throw new BadCredentialsException("Your staff seat subscription has expired. Please contact your shop owner.");
                }
            } else {
                // Staff is on the 1 included seat covered by owner's main subscription
                if (user.getOwner() == null || !entitlementService.hasActiveOwnerEntitlement(user.getOwner().getId())) {
                    throw new BadCredentialsException("Your shop's main subscription has expired. Please contact your shop owner.");
                }
            }
        }

        // Lockout check
        if (user.getPinLockedUntil() != null && user.getPinLockedUntil().isAfter(now)) {
            long minutesRemaining = ChronoUnit.MINUTES.between(now, user.getPinLockedUntil()) + 1;
            throw new AccountLockedException("Account temporarily locked due to multiple failed PIN attempts. Please try again after " + minutesRemaining + " minute(s) or reset your PIN.");
        }

        // Validate 6-digit PIN
        boolean pinMatches = passwordEncoder.matches(request.getPin(), user.getPassword());

        if (!pinMatches) {
            int attempts = (user.getFailedPinAttempts() != null ? user.getFailedPinAttempts() : 0) + 1;
            user.setFailedPinAttempts(attempts);

            if (attempts >= 5) {
                user.setPinLockedUntil(now.plusMinutes(15));
                userRepository.save(user);
                throw new AccountLockedException("Incorrect PIN. Account locked for 15 minutes due to 5 consecutive failed attempts. You can reset your PIN to regain access immediately.");
            } else {
                userRepository.save(user);
                try {
                    // Progressive delay (200ms per attempt)
                    Thread.sleep(Math.min(attempts * 200L, 800L));
                } catch (InterruptedException ignored) {}
                int remaining = 5 - attempts;
                throw new BadCredentialsException("Invalid mobile number or PIN. " + remaining + " attempt(s) remaining before account lockout.");
            }
        }

        // Reset failed attempts on success
        user.setFailedPinAttempts(0);
        user.setPinLockedUntil(null);

        if (user.isTemporaryPin()) {
            throw new BadCredentialsException("First-time device enrollment required. Please complete first-time enrollment using your temporary PIN.");
        }

        // Device-bound check if active device is enrolled
        Optional<DeviceRegistration> activeDeviceOpt = deviceRegistrationRepository.findByUserIdAndEnrollmentStatus(user.getId(), "ACTIVE");
        if (activeDeviceOpt.isPresent()) {
            DeviceRegistration activeDevice = activeDeviceOpt.get();
            String presentedDrid = request.getDeviceRegistrationId();
            if (presentedDrid == null || presentedDrid.isBlank() || !presentedDrid.equals(activeDevice.getDeviceRegistrationId())) {
                throw new DeviceMismatchException(
                        "Login blocked: This account is enrolled on another device (" + activeDevice.getDeviceName() + "). " +
                        "To use this device, a device replacement request must be submitted and approved by your shop owner or an administrator."
                );
            }
            activeDevice.setLastActiveAt(now);
            deviceRegistrationRepository.save(activeDevice);
        }

        user.setLastLoginAt(now);
        if (clientIp != null && !clientIp.isBlank()) {
            user.setLastLoginIp(clientIp);
        }
        if (request.getDeviceFingerprint() != null && !request.getDeviceFingerprint().isBlank()) {
            user.setLastDeviceFingerprint(request.getDeviceFingerprint());
        }

        // Single active device session enforcement
        String sessionId = UUID.randomUUID().toString();
        userSessionRepository.findByUserIdAndStatus(user.getId(), "ACTIVE").ifPresent(old -> {
            old.setStatus("REVOKED");
            old.setRevokedAt(now);
            userSessionRepository.save(old);
        });
        UserSession newSession = new UserSession(user, sessionId, request.getDeviceFingerprint(), clientIp, null);
        userSessionRepository.save(newSession);
        user.setActiveSessionId(sessionId);
        userRepository.save(user);

        String tokenSubject = user.getPhone() != null ? user.getPhone() : user.getEmail();
        String token = tokenProvider.generateTokenForUsername(tokenSubject, sessionId);

        return buildAuthResponse(token, user);
    }

    @Transactional(noRollbackFor = {BadCredentialsException.class, AccountLockedException.class})
    public AuthResponse login(AuthRequest request) {
        String identifier = request.getEmail().trim();
        User user = userRepository.findByIdentifier(identifier)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        LocalDateTime now = LocalDateTime.now(clock);

        if (user.isRevoked()) {
            throw new BadCredentialsException("Your account access has been revoked by the owner.");
        }

        if (user.getRole() == UserRole.ROLE_STAFF) {
            SubscriptionSeat seat = seatRepository.findByAssignedUserIdAndStatus(user.getId(), SubscriptionStatus.ACTIVE).orElse(null);
            LocalDate today = LocalDate.now(clock);
            if (seat != null) {
                if (seat.getEndDate() == null || today.isAfter(seat.getEndDate())) {
                    throw new BadCredentialsException("Your staff seat subscription has expired. Please contact your shop owner.");
                }
            } else {
                // Staff is on the 1 included seat covered by owner's main subscription
                if (user.getOwner() == null || !entitlementService.hasActiveOwnerEntitlement(user.getOwner().getId())) {
                    throw new BadCredentialsException("Your shop's main subscription has expired. Please contact your shop owner.");
                }
            }
        }
        if (user.getPinLockedUntil() != null && user.getPinLockedUntil().isAfter(now)) {
            long minutesRemaining = ChronoUnit.MINUTES.between(now, user.getPinLockedUntil()) + 1;
            throw new AccountLockedException("Account temporarily locked due to multiple failed attempts. Please try again after " + minutesRemaining + " minute(s) or reset your PIN.");
        }

        boolean pwdMatches = passwordEncoder.matches(request.getPassword(), user.getPassword());

        if (!pwdMatches) {
            int attempts = (user.getFailedPinAttempts() != null ? user.getFailedPinAttempts() : 0) + 1;
            user.setFailedPinAttempts(attempts);
            if (attempts >= 5) {
                user.setPinLockedUntil(now.plusMinutes(15));
                userRepository.save(user);
                throw new AccountLockedException("Incorrect credentials. Account locked for 15 minutes due to 5 consecutive failed attempts.");
            } else {
                userRepository.save(user);
                int remaining = 5 - attempts;
                throw new BadCredentialsException("Incorrect credentials. " + remaining + " attempt(s) remaining before account lockout.");
            }
        }

        user.setFailedPinAttempts(0);
        user.setPinLockedUntil(null);
        user.setLastLoginAt(now);

        // Single active device session enforcement
        String sessionId = UUID.randomUUID().toString();
        userSessionRepository.findByUserIdAndStatus(user.getId(), "ACTIVE").ifPresent(old -> {
            old.setStatus("REVOKED");
            old.setRevokedAt(now);
            userSessionRepository.save(old);
        });
        UserSession newSession = new UserSession(user, sessionId, null, null, null);
        userSessionRepository.save(newSession);
        user.setActiveSessionId(sessionId);
        userRepository.save(user);

        String tokenSubject = user.getPhone() != null ? user.getPhone() : user.getEmail();
        String token = tokenProvider.generateTokenForUsername(tokenSubject, sessionId);

        return buildAuthResponse(token, user);
    }

    /**
     * Resets 6-digit PIN.
     * When currentPin is supplied, verifies ownership against existing PIN.
     * Revokes all previous sessions immediately (sets credentialsUpdatedAt).
     * Clears lockout.
     * Preserves subscription and trial history without resetting eligibility.
     */
    @Transactional
    public void resetPin(PinResetRequest request) {
        if (request.getPhone() == null || request.getPhone().isBlank()) {
            throw new IllegalArgumentException("Mobile number is required.");
        }
        if (request.getNewPin() == null || !request.getNewPin().matches("^\\d{6}$")) {
            throw new IllegalArgumentException("New PIN must be exactly 6 digits.");
        }
        if (request.getConfirmPin() == null || !request.getNewPin().equals(request.getConfirmPin())) {
            throw new IllegalArgumentException("New PIN and Confirm PIN do not match.");
        }

        String normalizedPhone = phoneNormalizationService.normalize(request.getPhone());
        User user = userRepository.findByPhone(normalizedPhone)
                .or(() -> normalizedPhone.startsWith("+91") ? userRepository.findByPhone(normalizedPhone.substring(3)) : Optional.empty())
                .orElseThrow(() -> new IllegalArgumentException("No account found with mobile number: " + normalizedPhone));

        if (request.getCurrentPin() != null && !request.getCurrentPin().isBlank()) {
            if (!passwordEncoder.matches(request.getCurrentPin(), user.getPassword())) {
                throw new BadCredentialsException("Current PIN is incorrect.");
            }
        }

        user.setPassword(passwordEncoder.encode(request.getNewPin()));
        user.setCredentialsUpdatedAt(clock.instant());
        user.setFailedPinAttempts(0);
        user.setPinLockedUntil(null);
        if (request.getDeviceFingerprint() != null && !request.getDeviceFingerprint().isBlank()) {
            user.setLastDeviceFingerprint(request.getDeviceFingerprint());
        }
        userRepository.save(user);
    }

    /**
     * Admin-authenticated PIN reset for customer support assistance.
     * Returns the target owner ID.
     */
    @Transactional
    public Long adminResetPin(String phone, String newPin) {
        if (newPin == null || !newPin.matches("^\\d{6}$")) {
            throw new IllegalArgumentException("New PIN must be exactly 6 digits.");
        }
        String normalizedPhone = phoneNormalizationService.normalize(phone);
        User user = userRepository.findByPhone(normalizedPhone)
                .orElseThrow(() -> new IllegalArgumentException("No account found with mobile number: " + normalizedPhone));
        user.setPassword(passwordEncoder.encode(newPin));
        user.setCredentialsUpdatedAt(clock.instant());
        user.setFailedPinAttempts(0);
        user.setPinLockedUntil(null);
        userRepository.save(user);
        return user.getId();
    }

    /**
     * Legacy password reset compatibility.
     * Revokes active sessions and clears lockout.
     */
    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String normalizedPhone = phoneNormalizationService.normalize(request.getPhone());
        User user = userRepository.findByPhone(normalizedPhone)
                .or(() -> normalizedPhone.startsWith("+91") ? userRepository.findByPhone(normalizedPhone.substring(3)) : Optional.empty())
                .orElseThrow(() -> new IllegalArgumentException("No account found with mobile number: " + normalizedPhone));

        if (request.getCurrentPassword() != null && !request.getCurrentPassword().isBlank()) {
            if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
                throw new BadCredentialsException("Current password is incorrect.");
            }
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setCredentialsUpdatedAt(clock.instant());
        user.setFailedPinAttempts(0);
        user.setPinLockedUntil(null);
        userRepository.save(user);
    }

    private AuthResponse buildAuthResponse(String token, User user) {
        Shop shop = null;
        if (user.getRole() == UserRole.ROLE_OWNER || user.getRole() == UserRole.ROLE_ADMIN) {
            shop = shopRepository.findFirstByOwnerIdOrderByIsPrimaryDescCreatedAtAsc(user.getId()).orElse(null);
            if (shop == null) {
                shop = shopRepository.findByOwnerId(user.getId()).orElse(null);
            }
        } else if (user.getRole() == UserRole.ROLE_STAFF) {
            List<ShopMembership> memberships = membershipRepository.findAllByUserIdAndStatus(user.getId(), "ACTIVE");
            if (!memberships.isEmpty()) {
                shop = memberships.get(0).getShop();
            }
        }

        Long shopId = shop != null ? shop.getId() : null;
        String shopName = shop != null ? shop.getName() : null;
        String category = shop != null ? shop.getCategory() : null;

        boolean activeSubscription = false;
        long daysRemaining = 0;

        if (user.getRole() == UserRole.ROLE_OWNER && user.getId() != null) {
            activeSubscription = entitlementService.hasActiveOwnerEntitlement(user.getId());
            Subscription sub = subscriptionRepository.findTopByOwnerIdOrderByEndDateDesc(user.getId()).orElse(null);
            if (sub == null && shop != null) {
                sub = subscriptionRepository.findTopByShopIdOrderByEndDateDesc(shop.getId()).orElse(null);
            }
            if (activeSubscription && sub != null && sub.getEndDate() != null) {
                LocalDate today = LocalDate.now(clock);
                daysRemaining = Math.max(0, ChronoUnit.DAYS.between(today, sub.getEndDate()));
            }
        } else if (shop != null) {
            activeSubscription = entitlementService.hasActiveEntitlement(shop.getId());
            Subscription sub = subscriptionRepository.findTopByShopIdOrderByEndDateDesc(shop.getId()).orElse(null);
            if (activeSubscription && sub != null && sub.getEndDate() != null) {
                LocalDate today = LocalDate.now(clock);
                daysRemaining = Math.max(0, ChronoUnit.DAYS.between(today, sub.getEndDate()));
            }
        }

        AuthResponse resp = new AuthResponse(
                token,
                user.getEmail(),
                user.getPhone(),
                user.getFullName(),
                user.getRole(),
                shopId,
                shopName,
                category,
                activeSubscription,
                daysRemaining
        );

        if (user.getRole() == UserRole.ROLE_OWNER) {
            resp.setBusinesses(branchService.getBusinessesAndBranches(user.getId()));
            resp.setBranches(branchService.getAllBranches(user.getId()));
        } else if (user.getRole() == UserRole.ROLE_STAFF) {
            List<ShopMembership> memberships = membershipRepository.findAllByUserIdAndStatus(user.getId(), "ACTIVE");
            List<BranchSummaryResponse> staffBranches = new ArrayList<>();
            for (ShopMembership m : memberships) {
                BranchSummaryResponse b = new BranchSummaryResponse();
                Shop s = m.getShop();
                b.setId(s.getId());
                b.setName(s.getName());
                b.setBranchName(s.getBranchName() != null ? s.getBranchName() : s.getName());
                b.setCategory(s.getCategory());
                b.setIsPrimary(s.isPrimary());
                b.setRegistrationStatus(s.getRegistrationStatus());
                staffBranches.add(b);
            }
            resp.setBranches(staffBranches);
        }

        return resp;
    }

    /**
     * Securely deletes the shop owner account.
     * Account deletion does NOT delete the trial eligibility ledger.
     */
    @Transactional
    public void deleteAccount(String identifier) {
        User user = userRepository.findByIdentifier(identifier)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + identifier));

        String userPhone = user.getPhone();
        if (userPhone != null) {
            trialEligibilityLedgerRepository.findByNormalizedPhone(userPhone).ifPresent(ledger -> {
                ledger.setRegistrationStatus("ACCOUNT_DELETED");
                ledger.setOwnerId(null);
                ledger.setShopId(null);
                trialEligibilityLedgerRepository.save(ledger);
            });
        }

        userSessionRepository.deleteAllByUserId(user.getId());
        membershipRepository.deleteAllByUserId(user.getId());
        paymentRecordRepository.deleteAll(paymentRecordRepository.findAllByOwnerIdOrderByCreatedAtDesc(user.getId()));
        seatRepository.deleteAll(seatRepository.findAllByOwnerId(user.getId()));

        List<Shop> allShops = shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(user.getId());
        for (Shop s : allShops) {
            subscriptionRepository.deleteAll(subscriptionRepository.findByShopIdOrderByCreatedAtDesc(s.getId()));
            var sessions = sessionRepository.findByShopIdOrderBySessionDateDesc(s.getId());
            for (var sess : sessions) {
                customerRepository.deleteAll(customerRepository.findByQueueSessionIdOrderByTokenSequenceAsc(sess.getId()));
            }
            sessionRepository.deleteAll(sessions);
            shopRepository.delete(s);
        }

        businessRepository.deleteAll(businessRepository.findAllByOwnerIdOrderByCreatedAtAsc(user.getId()));

        userRepository.delete(user);
    }
}
