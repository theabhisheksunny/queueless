package com.queueless.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class StaffSummaryResponse {
    private Long id;
    private String fullName;
    private String phone;
    private String role;
    private String status;
    private List<Long> assignedBranchIds = new ArrayList<>();
    private List<String> assignedBranchNames = new ArrayList<>();
    private String activeSessionId;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private Boolean temporaryPin = false;
    private String registeredDeviceName;
    private String devicePlatform;
    private LocalDateTime deviceEnrolledAt;
    private LocalDateTime deviceLastActiveAt;
    private String deviceEnrollmentStatus;
    private Boolean hasPendingReplacement = false;
    private Long pendingReplacementId;
    private String seatPlanType;
    private java.time.LocalDate seatEndDate;

    public StaffSummaryResponse() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<Long> getAssignedBranchIds() {
        return assignedBranchIds;
    }

    public void setAssignedBranchIds(List<Long> assignedBranchIds) {
        this.assignedBranchIds = assignedBranchIds;
    }

    public List<String> getAssignedBranchNames() {
        return assignedBranchNames;
    }

    public void setAssignedBranchNames(List<String> assignedBranchNames) {
        this.assignedBranchNames = assignedBranchNames;
    }

    public String getActiveSessionId() {
        return activeSessionId;
    }

    public void setActiveSessionId(String activeSessionId) {
        this.activeSessionId = activeSessionId;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Boolean getTemporaryPin() {
        return temporaryPin;
    }

    public void setTemporaryPin(Boolean temporaryPin) {
        this.temporaryPin = temporaryPin;
    }

    public String getRegisteredDeviceName() {
        return registeredDeviceName;
    }

    public void setRegisteredDeviceName(String registeredDeviceName) {
        this.registeredDeviceName = registeredDeviceName;
    }

    public String getDevicePlatform() {
        return devicePlatform;
    }

    public void setDevicePlatform(String devicePlatform) {
        this.devicePlatform = devicePlatform;
    }

    public LocalDateTime getDeviceEnrolledAt() {
        return deviceEnrolledAt;
    }

    public void setDeviceEnrolledAt(LocalDateTime deviceEnrolledAt) {
        this.deviceEnrolledAt = deviceEnrolledAt;
    }

    public LocalDateTime getDeviceLastActiveAt() {
        return deviceLastActiveAt;
    }

    public void setDeviceLastActiveAt(LocalDateTime deviceLastActiveAt) {
        this.deviceLastActiveAt = deviceLastActiveAt;
    }

    public String getDeviceEnrollmentStatus() {
        return deviceEnrollmentStatus;
    }

    public void setDeviceEnrollmentStatus(String deviceEnrollmentStatus) {
        this.deviceEnrollmentStatus = deviceEnrollmentStatus;
    }

    public Boolean getHasPendingReplacement() {
        return hasPendingReplacement;
    }

    public void setHasPendingReplacement(Boolean hasPendingReplacement) {
        this.hasPendingReplacement = hasPendingReplacement;
    }

    public Long getPendingReplacementId() {
        return pendingReplacementId;
    }

    public void setPendingReplacementId(Long pendingReplacementId) {
        this.pendingReplacementId = pendingReplacementId;
    }

    public String getSeatPlanType() {
        return seatPlanType;
    }

    public void setSeatPlanType(String seatPlanType) {
        this.seatPlanType = seatPlanType;
    }

    public java.time.LocalDate getSeatEndDate() {
        return seatEndDate;
    }

    public void setSeatEndDate(java.time.LocalDate seatEndDate) {
        this.seatEndDate = seatEndDate;
    }
}
