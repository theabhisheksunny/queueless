package com.queueless.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class PhoneNormalizationService {

    private final String hmacSecret;

    public PhoneNormalizationService(@Value("${app.jwt.secret:queueless-default-secure-hmac-key-2026-32bytes}") String hmacSecret) {
        this.hmacSecret = hmacSecret;
    }

    /**
     * Normalizes an Indian mobile phone number into canonical international format: +91XXXXXXXXXX
     * Handles:
     * - "9876543210" -> "+919876543210"
     * - "09876543210" -> "+919876543210"
     * - "919876543210" -> "+919876543210"
     * - "+91 98765 43210" -> "+919876543210"
     * - "(+91) 98765-43210" -> "+919876543210"
     */
    public String normalize(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            throw new IllegalArgumentException("Mobile phone number is required.");
        }

        // Strip everything except digits
        String digits = rawPhone.replaceAll("[^0-9]", "");

        // If 12 digits and starts with 91, strip the country code 91
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }
        // If 11 digits and starts with 0 (national trunk prefix), strip 0
        else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }

        // Verify that the remaining digits form a valid 10-digit Indian mobile number:
        // Starts with 6, 7, 8, or 9
        if (!digits.matches("^[6-9]\\d{9}$")) {
            throw new IllegalArgumentException("Invalid Indian mobile number. Must be a valid 10-digit number starting with 6, 7, 8, or 9.");
        }

        return "+91" + digits;
    }

    /**
     * Computes HMAC-SHA256 of the normalized phone number for persistent pseudonymized anti-abuse indexing.
     */
    public String computeHmac(String normalizedPhone) {
        try {
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKey);
            byte[] hmacBytes = sha256Hmac.doFinal(normalizedPhone.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            // Fallback to SHA-256 digest
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(normalizedPhone.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (Exception ex) {
                throw new RuntimeException("Failed to hash phone number", ex);
            }
        }
    }
}
