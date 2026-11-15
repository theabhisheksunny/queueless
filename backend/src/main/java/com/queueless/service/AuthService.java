package com.queueless.service;

import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.exception.AccountLockedException;
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
import java.util.Optional;

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
        user = userRepository.save(user);

        // Create Shop (Duplicate shop names are permitted; shops are strictly keyed by internal shopId)
        Shop shop = new Shop(
                user,
                request.getShopName() != null ? request.getShopName().trim() : "My Shop",
                request.getCategory() != null ? request.getCategory().trim() : "General",
                request.getAvgServiceDurationMinutes() != null ? request.getAvgServiceDurationMinutes() : 15
        );
        shop = shopRepository.save(shop);

        // Grant exactly 7 calendar days trial
        LocalDate today = LocalDate.now(clock);
        LocalDate expiryDate = today.plusDays(7);
        LocalDateTime trialEndsAt = nowUtc.plusDays(7);

        Subscription trial = new Subscription(
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
        String token = tokenProvider.generateTokenForUsername(tokenSubject);

        return new AuthResponse(
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

        // Lockout check
        if (user.getPinLockedUntil() != null && user.getPinLockedUntil().isAfter(now)) {
            long minutesRemaining = ChronoUnit.MINUTES.between(now, user.getPinLockedUntil()) + 1;
            throw new AccountLockedException("Account temporarily locked due to multiple failed PIN attempts. Please try again after " + minutesRemaining + " minute(s) or reset your PIN.");
        }

        // Validate 6-digit PIN
        if (!passwordEncoder.matches(request.getPin(), user.getPassword())) {
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
        user.setLastLoginAt(now);
        if (clientIp != null && !clientIp.isBlank()) {
            user.setLastLoginIp(clientIp);
        }
        if (request.getDeviceFingerprint() != null && !request.getDeviceFingerprint().isBlank()) {
            user.setLastDeviceFingerprint(request.getDeviceFingerprint());
        }
        userRepository.save(user);

        String tokenSubject = user.getPhone() != null ? user.getPhone() : user.getEmail();
        String token = tokenProvider.generateTokenForUsername(tokenSubject);

        return buildAuthResponse(token, user);
    }

    @Transactional(noRollbackFor = {BadCredentialsException.class, AccountLockedException.class})
    public AuthResponse login(AuthRequest request) {
        String identifier = request.getEmail().trim();
        User user = userRepository.findByIdentifier(identifier)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        LocalDateTime now = LocalDateTime.now(clock);
        if (user.getPinLockedUntil() != null && user.getPinLockedUntil().isAfter(now)) {
            long minutesRemaining = ChronoUnit.MINUTES.between(now, user.getPinLockedUntil()) + 1;
            throw new AccountLockedException("Account temporarily locked due to multiple failed attempts. Please try again after " + minutesRemaining + " minute(s) or reset your PIN.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
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
        userRepository.save(user);

        String tokenSubject = user.getPhone() != null ? user.getPhone() : user.getEmail();
        String token = tokenProvider.generateTokenForUsername(tokenSubject);

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
     */
    @Transactional
    public void adminResetPin(String phone, String newPin) {
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
    }

    /**
     * Legacy password reset compatibility.
     * Revokes active sessions and clears lockout.
     */
    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String normalizedPhone = phoneNormalizationService.normalize(request.getPhone());
        User user = userRepository.findByPhone(normalizedPhone)
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
        Shop shop = shopRepository.findByOwnerId(user.getId()).orElse(null);

        Long shopId = shop != null ? shop.getId() : null;
        String shopName = shop != null ? shop.getName() : null;
        String category = shop != null ? shop.getCategory() : null;

        boolean activeSubscription = false;
        long daysRemaining = 0;

        if (shop != null) {
            activeSubscription = entitlementService.hasActiveEntitlement(shop.getId());
            Subscription sub = subscriptionRepository.findTopByShopIdOrderByEndDateDesc(shop.getId()).orElse(null);
            if (activeSubscription && sub != null && sub.getEndDate() != null) {
                LocalDate today = LocalDate.now(clock);
                daysRemaining = Math.max(0, ChronoUnit.DAYS.between(today, sub.getEndDate()));
            }
        }

        return new AuthResponse(
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

        Shop shop = shopRepository.findByOwnerId(user.getId()).orElse(null);
        if (shop != null) {
            subscriptionRepository.deleteAll(subscriptionRepository.findByShopIdOrderByCreatedAtDesc(shop.getId()));
            var sessions = sessionRepository.findByShopIdOrderBySessionDateDesc(shop.getId());
            for (var sess : sessions) {
                customerRepository.deleteAll(customerRepository.findByQueueSessionIdOrderByTokenSequenceAsc(sess.getId()));
            }
            sessionRepository.deleteAll(sessions);
            shopRepository.delete(shop);
        }

        userRepository.delete(user);
    }
}
