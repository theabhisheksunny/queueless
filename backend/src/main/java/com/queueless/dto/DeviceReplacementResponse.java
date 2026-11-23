package com.queueless.dto;

import java.time.LocalDateTime;

public class DeviceReplacementResponse {

    private Long id;
    private Long userId;
    private String userName;
    private String userPhone;
    private Long oldDeviceId;
    private String oldDeviceName;
    private String requestedDeviceName;
    private String requestedDevicePlatform;
    private String requestedDeviceRegistrationId;
    private String requestStatus;
    private LocalDateTime requestedAt;
    private LocalDateTime expiresAt;
    private String reviewedBy;
    private String approvalRole;
    private LocalDateTime reviewedAt;
    private String rejectionReason;

    public DeviceReplacementResponse() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserPhone() {
        return userPhone;
    }

    public void setUserPhone(String userPhone) {
        this.userPhone = userPhone;
    }

    public Long getOldDeviceId() {
        return oldDeviceId;
    }

    public void setOldDeviceId(Long oldDeviceId) {
        this.oldDeviceId = oldDeviceId;
    }

    public String getOldDeviceName() {
        return oldDeviceName;
    }

    public void setOldDeviceName(String oldDeviceName) {
        this.oldDeviceName = oldDeviceName;
    }

    public String getRequestedDeviceName() {
        return requestedDeviceName;
    }

    public void setRequestedDeviceName(String requestedDeviceName) {
        this.requestedDeviceName = requestedDeviceName;
    }

    public String getNewDeviceName() {
        return requestedDeviceName;
    }

    public void setNewDeviceName(String newDeviceName) {
        this.requestedDeviceName = newDeviceName;
    }

    public String getRequestedDevicePlatform() {
        return requestedDevicePlatform;
    }

    public void setRequestedDevicePlatform(String requestedDevicePlatform) {
        this.requestedDevicePlatform = requestedDevicePlatform;
    }

    public String getNewDevicePlatform() {
        return requestedDevicePlatform;
    }

    public void setNewDevicePlatform(String newDevicePlatform) {
        this.requestedDevicePlatform = newDevicePlatform;
    }

    public String getRequestedDeviceRegistrationId() {
        return requestedDeviceRegistrationId;
    }

    public void setRequestedDeviceRegistrationId(String requestedDeviceRegistrationId) {
        this.requestedDeviceRegistrationId = requestedDeviceRegistrationId;
    }

    public String getRequestStatus() {
        return requestStatus;
    }

    public void setRequestStatus(String requestStatus) {
        this.requestStatus = requestStatus;
    }

    public String getStatus() {
        return requestStatus;
    }

    public void setStatus(String status) {
        this.requestStatus = status;
    }

    public String getUserFullName() {
        return userName;
    }

    public void setUserFullName(String userFullName) {
        this.userName = userFullName;
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
}
