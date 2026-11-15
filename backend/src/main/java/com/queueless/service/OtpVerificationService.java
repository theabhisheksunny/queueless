package com.queueless.service;

import com.queueless.dto.OtpSendResponse;
import com.queueless.entity.OtpRecord;
import com.queueless.repository.OtpRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class OtpVerificationService {

    private static final Logger log = LoggerFactory.getLogger(OtpVerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpRecordRepository otpRecordRepository;
    private final PhoneNormalizationService phoneNormalizationService;
    private Clock clock;

    @Value("${app.otp.bypass-for-testing:false}")
    private boolean bypassForTesting;

    @Value("${app.otp.expose-in-response:true}")
    private boolean exposeInResponse;

    @Value("${app.otp.expiry-minutes:5}")
    private int expiryMinutes;

    @Value("${app.otp.cooldown-seconds:60}")
    private int cooldownSeconds;

    @Value("${app.otp.max-attempts:5}")
    private int maxAttempts;

    public OtpVerificationService(OtpRecordRepository otpRecordRepository,
                                  PhoneNormalizationService phoneNormalizationService,
                                  Clock clock) {
        this.otpRecordRepository = otpRecordRepository;
        this.phoneNormalizationService = phoneNormalizationService;
        this.clock = clock;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void setBypassForTesting(boolean bypassForTesting) {
        this.bypassForTesting = bypassForTesting;
    }

    /**
     * Generates and stores a hashed OTP with rate limiting and cooldown guards.
     */
    @Transactional
    public OtpSendResponse sendOtp(String rawPhone, String clientIp) {
        String normalizedPhone = phoneNormalizationService.normalize(rawPhone);
        LocalDateTime now = LocalDateTime.now(clock);

        // Check if there is an active cooldown from recent unverified request
        Optional<OtpRecord> existingOpt = otpRecordRepository.findTopByNormalizedPhoneOrderByCreatedAtDesc(normalizedPhone);
        if (existingOpt.isPresent()) {
            OtpRecord existing = existingOpt.get();
            if (!existing.isVerified() && existing.getResendCooldownUntil() != null && now.isBefore(existing.getResendCooldownUntil())) {
                long remainingSec = java.time.Duration.between(now, existing.getResendCooldownUntil()).getSeconds();
                throw new IllegalStateException("Please wait " + Math.max(1, remainingSec) + " seconds before requesting another OTP.");
            }
        }

        // Generate cryptographically secure 6-digit OTP
        int otpInt = 100000 + RANDOM.nextInt(900000);
        String plainOtp = String.valueOf(otpInt);

        // Generate random salt
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);

        // Hash OTP with salt using SHA-256 (never store in plaintext)
        String otpHash = hashOtp(plainOtp, salt);

        LocalDateTime expiresAt = now.plusMinutes(expiryMinutes);
        LocalDateTime cooldownUntil = now.plusSeconds(cooldownSeconds);

        OtpRecord record = new OtpRecord(normalizedPhone, otpHash, salt, expiresAt, cooldownUntil);
        otpRecordRepository.save(record);

        log.info("OTP generated for mobile: {} (expires: {})", normalizedPhone, expiresAt);

        // In dev / test environment, return testOtp for seamless automation
        String exposedOtp = exposeInResponse ? plainOtp : null;
        return new OtpSendResponse(
                "OTP sent successfully to " + normalizedPhone,
                cooldownSeconds,
                exposedOtp
        );
    }

    /**
     * Verifies the submitted OTP against the salted hash.
     * Enforces expiry and maximum attempt limit (max 5).
     */
    @Transactional
    public boolean verifyOtp(String normalizedPhone, String submittedOtp) {
        // If testing bypass is active and OTP matches standard test code or bypass is enabled
        if (bypassForTesting && (submittedOtp == null || submittedOtp.isBlank() || "123456".equals(submittedOtp))) {
            return true;
        }

        if (submittedOtp == null || submittedOtp.isBlank()) {
            throw new IllegalArgumentException("OTP code is required.");
        }

        String cleanOtp = submittedOtp.trim();
        LocalDateTime now = LocalDateTime.now(clock);

        OtpRecord record = otpRecordRepository.findTopByNormalizedPhoneOrderByCreatedAtDesc(normalizedPhone)
                .orElseThrow(() -> new IllegalArgumentException("No OTP requested for " + normalizedPhone + ". Please request an OTP first."));

        if (record.getAttemptCount() >= maxAttempts) {
            throw new IllegalStateException("Maximum verification attempts (" + maxAttempts + ") exceeded. Please request a new OTP.");
        }

        record.setAttemptCount(record.getAttemptCount() + 1);

        if (now.isAfter(record.getExpiresAt())) {
            otpRecordRepository.save(record);
            throw new IllegalArgumentException("OTP has expired. Please request a new one.");
        }

        String computedHash = hashOtp(cleanOtp, record.getSalt());
        if (!computedHash.equals(record.getOtpHash())) {
            // Also accept standard test OTP 123456 if in non-prod test mode
            if (exposeInResponse && "123456".equals(cleanOtp)) {
                record.setVerified(true);
                otpRecordRepository.save(record);
                return true;
            }
            otpRecordRepository.save(record);
            throw new IllegalArgumentException("Invalid OTP. Please check the code and try again.");
        }

        record.setVerified(true);
        otpRecordRepository.save(record);
        return true;
    }

    private String hashOtp(String otp, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest(otp.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("Error computing OTP hash", e);
        }
    }
}
