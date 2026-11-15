package com.queueless.service;

import com.queueless.dto.AuthRequest;
import com.queueless.dto.AuthResponse;
import com.queueless.dto.PasswordResetRequest;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.*;
import com.queueless.exception.TrialAlreadyConsumedException;
import com.queueless.repository.*;
import com.queueless.security.JwtTokenProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final OtpVerificationService otpVerificationService;
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
                       OtpVerificationService otpVerificationService,
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
        this.otpVerificationService = otpVerificationService;
        this.entitlementService = entitlementService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.clock = clock;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
        this.entitlementService.setClock(clock);
        this.otpVerificationService.setClock(clock);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail().trim().toLowerCase())) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }

        // Normalize Indian mobile number
        String normalizedPhone = phoneNormalizationService.normalize(request.getPhone());

        // Check if an account already exists with this phone number
        if (userRepository.existsByPhone(normalizedPhone)) {
            throw new IllegalArgumentException("An account with this verified mobile number already exists. Please log in.");
        }

        // Check persistent trial eligibility ledger (survives account deletion)
        if (trialEligibilityLedgerRepository.existsByNormalizedPhone(normalizedPhone)) {
            throw new TrialAlreadyConsumedException(
                    "A 7-day free trial has already been claimed for this verified mobile number. This identity is not eligible for another free trial. Please log in or contact support."
            );
        }

        // Verify mobile OTP
        otpVerificationService.verifyOtp(normalizedPhone, request.getOtp());

        // Create User
        User user = new User(
                request.getEmail().trim().toLowerCase(),
                passwordEncoder.encode(request.getPassword()),
                request.getFullName().trim(),
                normalizedPhone,
                UserRole.ROLE_OWNER
        );
        user = userRepository.save(user);

        // Create Shop (Duplicate shop names are permitted; shops are strictly keyed by internal shopId)
        Shop shop = new Shop(
                user,
                request.getShopName().trim(),
                request.getCategory() != null ? request.getCategory().trim() : "General",
                request.getAvgServiceDurationMinutes() != null ? request.getAvgServiceDurationMinutes() : 15
        );
        shop = shopRepository.save(shop);

        // Grant exactly 7 calendar days trial
        LocalDate today = LocalDate.now(clock);
        LocalDate expiryDate = today.plusDays(7);
        LocalDateTime nowUtc = LocalDateTime.now(clock);
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

        String token = tokenProvider.generateTokenForUsername(user.getEmail());

        return new AuthResponse(
                token,
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                shop.getId(),
                shop.getName(),
                shop.getCategory(),
                true,
                7
        );
    }

    @Transactional(readOnly = true)
    public AuthResponse login(AuthRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.getPassword())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String token = tokenProvider.generateToken(authentication);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

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
     * Resets password using mobile OTP verification.
     * Preserves subscription and trial history without resetting eligibility.
     */
    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String normalizedPhone = phoneNormalizationService.normalize(request.getPhone());
        otpVerificationService.verifyOtp(normalizedPhone, request.getOtp());

        User user = userRepository.findByPhone(normalizedPhone)
                .orElseThrow(() -> new IllegalArgumentException("No account found with verified mobile number: " + normalizedPhone));

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    /**
     * Securely deletes the shop owner account.
     * Crucial: Account deletion does NOT delete the trial eligibility ledger.
     * Future registration attempts with the same phone will be prevented from claiming a new trial.
     */
    @Transactional
    public void deleteAccount(String ownerEmail) {
        User user = userRepository.findByEmail(ownerEmail.trim().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + ownerEmail));

        String userPhone = user.getPhone();
        if (userPhone != null) {
            // Update ledger status to ACCOUNT_DELETED, unlinking owner and shop ID while preserving the trial record
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
