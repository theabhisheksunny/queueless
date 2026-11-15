package com.queueless.service.sms;

public interface SmsService {
    /**
     * Checks if a real SMS delivery provider (e.g. Twilio, Fast2SMS) or test provider is configured and available.
     * In production with no SMS provider configured, this returns false.
     */
    boolean isConfigured();

    /**
     * Dispatches an OTP SMS message to the normalized Indian mobile number.
     * Throws SmsProviderUnavailableException if no provider is configured.
     */
    void sendOtp(String normalizedPhone, String otp);

    /**
     * Returns the last sent OTP for the given phone number in test/mock environments.
     * Returns null in production environments.
     */
    default String getLastSentOtp(String normalizedPhone) {
        return null;
    }
}
