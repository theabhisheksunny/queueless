package com.queueless.security;

import java.util.regex.Pattern;

/**
 * Utility to sanitize sensitive query parameters and credential tokens from URLs and log messages.
 * Prevents leakage of tokens, tickets, passwords, PINs, and administrative keys in application logs.
 */
public final class LogSanitizer {

    private static final Pattern SENSITIVE_QUERY_PARAM_PATTERN = Pattern.compile(
            "(?i)(?<=[?&])(token|ticket|admin[-_]?key|password|pin|secret|auth|access[-_]?token)=([^&#\\s]+)"
    );

    private static final Pattern BEARER_HEADER_PATTERN = Pattern.compile(
            "(?i)bearer\\s+[a-zA-Z0-9._~+/-]+",
            Pattern.CASE_INSENSITIVE
    );

    private LogSanitizer() {}

    /**
     * Redacts sensitive query parameters (e.g. ticket=..., token=..., adminKey=...) from a URL or query string.
     */
    public static String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        return SENSITIVE_QUERY_PARAM_PATTERN.matcher(url).replaceAll("$1=[REDACTED]");
    }

    /**
     * Redacts sensitive query parameters and bearer authorization tokens from general log messages.
     */
    public static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String sanitized = SENSITIVE_QUERY_PARAM_PATTERN.matcher(message).replaceAll("$1=[REDACTED]");
        return BEARER_HEADER_PATTERN.matcher(sanitized).replaceAll("Bearer [REDACTED]");
    }
}
