package com.queueless.util;

import java.security.SecureRandom;

/**
 * Generates cryptographically secure, high-entropy, opaque public merchant identifiers.
 * Format: "pm_" followed by 16 random Base62 characters.
 * Entropy: 62^16 ≈ 4.76 x 10^28 combinations (≈ 95 bits of entropy).
 * Eliminates sequential internal database ID disclosure.
 */
public final class PublicMerchantCodeGenerator {

    public static final String PREFIX = "pm_";
    public static final int RANDOM_CHARS_LENGTH = 16;
    private static final String BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PublicMerchantCodeGenerator() {
        // Utility class
    }

    /**
     * Generates a new cryptographically random opaque public merchant code.
     * Guaranteed to never derive from internal database IDs.
     */
    public static String generate() {
        StringBuilder sb = new StringBuilder(PREFIX.length() + RANDOM_CHARS_LENGTH);
        sb.append(PREFIX);
        for (int i = 0; i < RANDOM_CHARS_LENGTH; i++) {
            int idx = SECURE_RANDOM.nextInt(BASE62_CHARS.length());
            sb.append(BASE62_CHARS.charAt(idx));
        }
        return sb.toString();
    }

    /**
     * Validates whether a given string adheres to the canonical public merchant code format.
     */
    public static boolean isValid(String code) {
        if (code == null || code.length() != PREFIX.length() + RANDOM_CHARS_LENGTH) {
            return false;
        }
        if (!code.startsWith(PREFIX)) {
            return false;
        }
        for (int i = PREFIX.length(); i < code.length(); i++) {
            char c = code.charAt(i);
            if (BASE62_CHARS.indexOf(c) == -1) {
                return false;
            }
        }
        return true;
    }
}
