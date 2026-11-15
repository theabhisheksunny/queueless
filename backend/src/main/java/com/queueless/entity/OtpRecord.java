package com.queueless.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "otp_records", indexes = {
    @Index(name = "idx_otp_phone", columnList = "normalizedPhone")
})
public class OtpRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String normalizedPhone;

    @Column(nullable = false, length = 128)
    private String otpHash;

    @Column(nullable = false, length = 32)
    private String salt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private Integer attemptCount = 0;

    private LocalDateTime resendCooldownUntil;

    @Column(nullable = false)
    private Boolean verified = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.attemptCount == null) {
            this.attemptCount = 0;
        }
        if (this.verified == null) {
            this.verified = false;
        }
    }

    public OtpRecord() {}

    public OtpRecord(String normalizedPhone, String otpHash, String salt, LocalDateTime expiresAt, LocalDateTime resendCooldownUntil) {
        this.normalizedPhone = normalizedPhone;
        this.otpHash = otpHash;
        this.salt = salt;
        this.expiresAt = expiresAt;
        this.resendCooldownUntil = resendCooldownUntil;
        this.attemptCount = 0;
        this.verified = false;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNormalizedPhone() {
        return normalizedPhone;
    }

    public void setNormalizedPhone(String normalizedPhone) {
        this.normalizedPhone = normalizedPhone;
    }

    public String getOtpHash() {
        return otpHash;
    }

    public void setOtpHash(String otpHash) {
        this.otpHash = otpHash;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public LocalDateTime getResendCooldownUntil() {
        return resendCooldownUntil;
    }

    public void setResendCooldownUntil(LocalDateTime resendCooldownUntil) {
        this.resendCooldownUntil = resendCooldownUntil;
    }

    public Boolean getVerified() {
        return verified;
    }

    public boolean isVerified() {
        return Boolean.TRUE.equals(verified);
    }

    public void setVerified(Boolean verified) {
        this.verified = verified;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
