package com.queueless.service;

import com.queueless.dto.OtpSendResponse;
import com.queueless.entity.OtpRecord;
import com.queueless.exception.SmsProviderUnavailableException;
import com.queueless.repository.OtpRecordRepository;
import com.queueless.service.sms.SmsService;
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
    private final SmsService smsService;
    private Clock clock;

    @Value("${app.otp.expiry-minutes:5}")
    private int expiryMinutes;

    @Value("${app.otp.cooldown-seconds:60}")
    private int cooldownSeconds;

    @Value("${app.otp.max-attempts:5}")
    private int maxAttempts;

    public OtpVerificationService(OtpRecordRepository otpRecordRepository,
                                  PhoneNormalizationService phoneNormalizationService,
                                  SmsService smsService,
                                  Clock clock) {
        this.otpRecordRepository = otpRecordRepository;
        this.phoneNormalizationService = phoneNormalizationService;
        this.smsService = smsService;
        this.clock = clock;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Generates and dispatches a cryptographically secure 6-digit OTP.
     * Enforces that a real SMS provider is configured; never generates fake or demo OTPs.
     * Stores only a salted SHA-256 hash. Never exposes OTP in API responses or logs.
     */
    @Transactional
    public OtpSendResponse sendOtp(String rawPhone, String clientIp) {
        if (!smsService.isConfigured()) {
            throw new SmsProviderUnavailableException(
                    "SMS verification service is currently unavailable. No SMS gateway provider is configured. Please configure an SMS provider (e.g., Fast2SMS or Twilio) in application settings."
            );
        }

        String normalizedPhone = phoneNormalizationService.normalize(rawPhone);
        LocalDateTime now = LocalDateTime.now(clock);

        // Check active resend cooldown from previous request
        Optional<OtpRecord> existingOpt = otpRecordRepository.findTopByNormalizedPhoneOrderByCreatedAtDesc(normalizedPhone);
        if (existingOpt.isPresent()) {
            OtpRecord existing = existingOpt.get();
            if (!existing.isVerified() && existing.getResendCooldownUntil() != null && now.isBefore(existing.getResendCooldownUntil())) {
                long remainingSec = java.time.Duration.between(now, existing.getResendCooldownUntil()).getSeconds();
                throw new IllegalStateException("Please wait " + Math.max(1, remainingSec) + " seconds before requesting another OTP.");
            }
        }

        // Generate cryptographically secure random 6-digit OTP (100000 - 999999)
        int otpInt = 100000 + RANDOM.nextInt(900000);
        String plainOtp = String.valueOf(otpInt);

        // Generate cryptographically secure 16-byte random salt
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);

        // Hash OTP with salt using SHA-256 (plain OTP is never stored in DB)
        String otpHash = hashOtp(plainOtp, salt);

        LocalDateTime expiresAt = now.plusMinutes(expiryMinutes);
        LocalDateTime cooldownUntil = now.plusSeconds(cooldownSeconds);

        OtpRecord record = new OtpRecord(normalizedPhone, otpHash, salt, expiresAt, cooldownUntil);
        otpRecordRepository.save(record);

        // Dispatch OTP via the configured SMS delivery provider
        smsService.sendOtp(normalizedPhone, plainOtp);
        log.info("OTP generated and dispatched via SMS provider for mobile: {} (expires: {})", normalizedPhone, expiresAt);

        // Return confirmation without exposing OTP value
        return new OtpSendResponse(
                "OTP sent successfully to " + normalizedPhone,
                cooldownSeconds
        );
    }

    /**
     * Verifies the submitted OTP against the salted SHA-256 hash.
     * Enforces 5-minute expiry, maximum 5 attempts, and rejects all invalid or hardcoded codes.
     */
    @Transactional(noRollbackFor = {IllegalArgumentException.class, IllegalStateException.class})
    public boolean verifyOtp(String normalizedPhone, String submittedOtp) {
        if (submittedOtp == null || submittedOtp.isBlank()) {
            throw new IllegalArgumentException("OTP code is required.");
        }

        String cleanOtp = submittedOtp.trim();
        if (!cleanOtp.matches("^\\d{6}$")) {
            throw new IllegalArgumentException("OTP must be exactly 6 numeric digits.");
        }

        LocalDateTime now = LocalDateTime.now(clock);

        OtpRecord record = otpRecordRepository.findTopByNormalizedPhoneOrderByCreatedAtDesc(normalizedPhone)
                .orElseThrow(() -> new IllegalArgumentException("No OTP requested for " + normalizedPhone + ". Please request an OTP first."));

        if (record.isVerified()) {
            return true;
        }

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
            otpRecordRepository.save(record);
            int remaining = maxAttempts - record.getAttemptCount();
            if (remaining > 0) {
                throw new IllegalArgumentException("Invalid OTP. " + remaining + " attempt(s) remaining.");
            } else {
                throw new IllegalStateException("Maximum verification attempts (" + maxAttempts + ") exceeded. Please request a new OTP.");
            }
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
