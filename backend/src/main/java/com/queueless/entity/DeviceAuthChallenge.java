package com.queueless.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "device_auth_challenges", indexes = {
    @Index(name = "idx_challenge_str", columnList = "challenge"),
    @Index(name = "idx_challenge_user_status", columnList = "user_id, status")
})
public class DeviceAuthChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "device_registration_id", nullable = false, length = 100)
    private String deviceRegistrationId;

    @Column(nullable = false, unique = true, length = 128)
    private String challenge;

    @Column(nullable = false, length = 30)
    private String purpose = "DEVICE_LOGIN";

    @Column(nullable = false, length = 20)
    private String status = "PENDING"; // PENDING, CONSUMED, EXPIRED

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    public DeviceAuthChallenge() {}

    public DeviceAuthChallenge(User user, String deviceRegistrationId, String challenge, String purpose, LocalDateTime expiresAt) {
        this.user = user;
        this.deviceRegistrationId = deviceRegistrationId;
        this.challenge = challenge;
        this.purpose = purpose != null ? purpose : "DEVICE_LOGIN";
        this.status = "PENDING";
        this.createdAt = expiresAt != null ? expiresAt.minusSeconds(90) : LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }

    public boolean isExpired(LocalDateTime currentDateTime) {
        if (currentDateTime == null) return isExpired();
        return currentDateTime.isAfter(this.expiresAt);
    }

    public boolean isPending() {
        return "PENDING".equalsIgnoreCase(this.status) && !isExpired();
    }

    public boolean isPending(LocalDateTime currentDateTime) {
        return "PENDING".equalsIgnoreCase(this.status) && !isExpired(currentDateTime);
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getDeviceRegistrationId() {
        return deviceRegistrationId;
    }

    public void setDeviceRegistrationId(String deviceRegistrationId) {
        this.deviceRegistrationId = deviceRegistrationId;
    }

    public String getChallenge() {
        return challenge;
    }

    public void setChallenge(String challenge) {
        this.challenge = challenge;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(LocalDateTime consumedAt) {
        this.consumedAt = consumedAt;
    }
}
