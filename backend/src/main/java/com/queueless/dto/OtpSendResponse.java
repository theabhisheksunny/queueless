package com.queueless.dto;

public class OtpSendResponse {
    private String message;
    private long cooldownSeconds;

    public OtpSendResponse() {}

    public OtpSendResponse(String message, long cooldownSeconds) {
        this.message = message;
        this.cooldownSeconds = cooldownSeconds;
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
}
