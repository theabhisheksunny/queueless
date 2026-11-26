package com.queueless.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false, length = 100)
    private String fullName;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.ROLE_OWNER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_PENDING_REGISTRATION = "PENDING_REGISTRATION";

    @Column(nullable = false, length = 20)
    private String status = STATUS_ACTIVE; // ACTIVE, REVOKED, PENDING_REGISTRATION

    @Column(name = "active_session_id", length = 64)
    private String activeSessionId;

    @Column(name = "temporary_pin", nullable = false)
    private Boolean temporaryPin = false;

    @Column(name = "failed_pin_attempts")
    private Integer failedPinAttempts = 0;

    @Column(name = "pin_locked_until")
    private LocalDateTime pinLockedUntil;

    @Column(name = "credentials_updated_at")
    private Instant credentialsUpdatedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "last_login_ip", length = 64)
    private String lastLoginIp;

    @Column(name = "last_device_fingerprint", length = 255)
    private String lastDeviceFingerprint;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public User() {}

    public User(String email, String password, String fullName, String phone, UserRole role) {
        this.email = email;
        this.password = password;
        this.fullName = fullName;
        this.phone = phone;
        this.role = role != null ? role : UserRole.ROLE_OWNER;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public Integer getFailedPinAttempts() {
        return failedPinAttempts != null ? failedPinAttempts : 0;
    }

    public void setFailedPinAttempts(Integer failedPinAttempts) {
        this.failedPinAttempts = failedPinAttempts;
    }

    public LocalDateTime getPinLockedUntil() {
        return pinLockedUntil;
    }

    public void setPinLockedUntil(LocalDateTime pinLockedUntil) {
        this.pinLockedUntil = pinLockedUntil;
    }

    public Instant getCredentialsUpdatedAt() {
        return credentialsUpdatedAt;
    }

    public void setCredentialsUpdatedAt(Instant credentialsUpdatedAt) {
        this.credentialsUpdatedAt = credentialsUpdatedAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getLastLoginIp() {
        return lastLoginIp;
    }

    public void setLastLoginIp(String lastLoginIp) {
        this.lastLoginIp = lastLoginIp;
    }

    public String getLastDeviceFingerprint() {
        return lastDeviceFingerprint;
    }

    public void setLastDeviceFingerprint(String lastDeviceFingerprint) {
        this.lastDeviceFingerprint = lastDeviceFingerprint;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isRevoked() {
        return STATUS_REVOKED.equalsIgnoreCase(this.status);
    }

    public boolean isPendingRegistration() {
        return STATUS_PENDING_REGISTRATION.equalsIgnoreCase(this.status);
    }

    public String getActiveSessionId() {
        return activeSessionId;
    }

    public void setActiveSessionId(String activeSessionId) {
        this.activeSessionId = activeSessionId;
    }

    public Boolean getTemporaryPin() {
        return temporaryPin;
    }

    public boolean isTemporaryPin() {
        return Boolean.TRUE.equals(this.temporaryPin);
    }

    public void setTemporaryPin(Boolean temporaryPin) {
        this.temporaryPin = temporaryPin;
    }
}
