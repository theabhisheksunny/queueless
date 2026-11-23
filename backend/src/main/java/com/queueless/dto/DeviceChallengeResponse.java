package com.queueless.dto;

import java.time.LocalDateTime;

public class DeviceChallengeResponse {

    private String challenge;
    private LocalDateTime expiresAt;
    private Long validitySeconds;

    public DeviceChallengeResponse() {}

    public DeviceChallengeResponse(String challenge, LocalDateTime expiresAt, Long validitySeconds) {
        this.challenge = challenge;
        this.expiresAt = expiresAt;
        this.validitySeconds = validitySeconds;
    }

    public String getChallenge() {
        return challenge;
    }

    public void setChallenge(String challenge) {
        this.challenge = challenge;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getValiditySeconds() {
        return validitySeconds;
    }

    public void setValiditySeconds(Long validitySeconds) {
        this.validitySeconds = validitySeconds;
    }
}
