package com.queueless.dto;

public class OtpSendResponse {
    private String message;
    private long cooldownSeconds;
    private String testOtp; // Only populated in non-production/test environments for automated testing

    public OtpSendResponse() {}

    public OtpSendResponse(String message, long cooldownSeconds, String testOtp) {
        this.message = message;
        this.cooldownSeconds = cooldownSeconds;
        this.testOtp = testOtp;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(long cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public String getTestOtp() {
        return testOtp;
    }

    public void setTestOtp(String testOtp) {
        this.testOtp = testOtp;
    }
}
