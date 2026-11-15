package com.queueless.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "trial_eligibility_ledger", indexes = {
    @Index(name = "idx_tel_phone", columnList = "normalized_phone", unique = true),
    @Index(name = "idx_tel_hmac", columnList = "phone_hmac")
})
public class TrialEligibilityLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "normalized_phone", nullable = false, unique = true, length = 32)
    private String normalizedPhone;

    @Column(name = "phone_hmac", nullable = false, length = 64)
    private String phoneHmac;

    @Column(name = "trial_started_at", nullable = false)
    private LocalDateTime trialStartedAt;

    @Column(name = "trial_ends_at", nullable = false)
    private LocalDateTime trialEndsAt;

    @Column(name = "trial_consumed_at", nullable = false)
    private LocalDateTime trialConsumedAt;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "shop_id")
    private Long shopId;

    @Column(name = "owner_email", length = 150)
    private String ownerEmail;

    @Column(name = "registration_status", nullable = false, length = 30)
    private String registrationStatus = "ACTIVE";

    @Column(name = "device_fingerprint", length = 128)
    private String deviceFingerprint;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "abuse_flags", length = 255)
    private String abuseFlags;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
        if (this.registrationStatus == null) {
            this.registrationStatus = "ACTIVE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public TrialEligibilityLedger() {}

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

    public String getPhoneHmac() {
        return phoneHmac;
    }

    public void setPhoneHmac(String phoneHmac) {
        this.phoneHmac = phoneHmac;
    }

    public LocalDateTime getTrialStartedAt() {
        return trialStartedAt;
    }

    public void setTrialStartedAt(LocalDateTime trialStartedAt) {
        this.trialStartedAt = trialStartedAt;
    }

    public LocalDateTime getTrialEndsAt() {
        return trialEndsAt;
    }

    public void setTrialEndsAt(LocalDateTime trialEndsAt) {
        this.trialEndsAt = trialEndsAt;
    }

    public LocalDateTime getTrialConsumedAt() {
        return trialConsumedAt;
    }

    public void setTrialConsumedAt(LocalDateTime trialConsumedAt) {
        this.trialConsumedAt = trialConsumedAt;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    public String getRegistrationStatus() {
        return registrationStatus;
    }

    public void setRegistrationStatus(String registrationStatus) {
        this.registrationStatus = registrationStatus;
    }

    public String getDeviceFingerprint() {
        return deviceFingerprint;
    }

    public void setDeviceFingerprint(String deviceFingerprint) {
        this.deviceFingerprint = deviceFingerprint;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getAbuseFlags() {
        return abuseFlags;
    }

    public void setAbuseFlags(String abuseFlags) {
        this.abuseFlags = abuseFlags;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
