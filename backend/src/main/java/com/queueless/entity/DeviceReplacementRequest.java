package com.queueless.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "device_replacement_requests", indexes = {
    @Index(name = "idx_device_rep_user", columnList = "user_id, request_status"),
    @Index(name = "idx_device_rep_status", columnList = "request_status")
})
public class DeviceReplacementRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private User user;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "old_device_id")
    private DeviceRegistration oldDevice;

    @Column(name = "requested_device_registration_id", length = 100)
    private String requestedDeviceRegistrationId;

    @Column(name = "requested_device_name", nullable = false, length = 120)
    private String requestedDeviceName;

    @Column(name = "requested_device_platform", nullable = false, length = 60)
    private String requestedDevicePlatform;

    @Column(name = "requested_public_key", nullable = false, columnDefinition = "TEXT")
    private String requestedPublicKey;

    @Column(name = "request_status", nullable = false, length = 20)
    private String requestStatus = "PENDING"; // PENDING, APPROVED, REJECTED, EXPIRED

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "approval_role", length = 20)
    private String approvalRole; // OWNER, ADMIN

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.requestedAt == null) {
            this.requestedAt = LocalDateTime.now();
        }
        if (this.expiresAt == null) {
            // Default 48 hours validity for replacement request
            this.expiresAt = LocalDateTime.now().plusHours(48);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public DeviceReplacementRequest() {}

    public DeviceReplacementRequest(User user, DeviceRegistration oldDevice, String requestedDeviceRegistrationId,
                                    String requestedDeviceName, String requestedDevicePlatform, String requestedPublicKey) {
        this.user = user;
        this.oldDevice = oldDevice;
        this.requestedDeviceRegistrationId = requestedDeviceRegistrationId;
        this.requestedDeviceName = requestedDeviceName;
        this.requestedDevicePlatform = requestedDevicePlatform;
        this.requestedPublicKey = requestedPublicKey;
        this.requestStatus = "PENDING";
        this.requestedAt = LocalDateTime.now();
        this.expiresAt = LocalDateTime.now().plusHours(48);
    }

    public DeviceReplacementRequest(User user, DeviceRegistration oldDevice, String requestedDeviceName,
                                    String requestedDevicePlatform, String requestedPublicKey) {
        this(user, oldDevice, null, requestedDeviceName, requestedDevicePlatform, requestedPublicKey);
    }

    public String getRequestedDeviceRegistrationId() {
        return requestedDeviceRegistrationId;
    }

    public void setRequestedDeviceRegistrationId(String requestedDeviceRegistrationId) {
        this.requestedDeviceRegistrationId = requestedDeviceRegistrationId;
    }

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

    public DeviceRegistration getOldDevice() {
        return oldDevice;
    }

    public void setOldDevice(DeviceRegistration oldDevice) {
        this.oldDevice = oldDevice;
    }

    public String getRequestedDeviceName() {
        return requestedDeviceName;
    }

    public void setRequestedDeviceName(String requestedDeviceName) {
        this.requestedDeviceName = requestedDeviceName;
    }

    public String getRequestedDevicePlatform() {
        return requestedDevicePlatform;
    }

    public void setRequestedDevicePlatform(String requestedDevicePlatform) {
        this.requestedDevicePlatform = requestedDevicePlatform;
    }

    public String getRequestedPublicKey() {
        return requestedPublicKey;
    }

    public void setRequestedPublicKey(String requestedPublicKey) {
        this.requestedPublicKey = requestedPublicKey;
    }

    public String getRequestStatus() {
        return requestStatus;
    }

    public void setRequestStatus(String requestStatus) {
        this.requestStatus = requestStatus;
    }

    public boolean isPending() {
        return "PENDING".equalsIgnoreCase(this.requestStatus);
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public String getApprovalRole() {
        return approvalRole;
    }

    public void setApprovalRole(String approvalRole) {
        this.approvalRole = approvalRole;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
