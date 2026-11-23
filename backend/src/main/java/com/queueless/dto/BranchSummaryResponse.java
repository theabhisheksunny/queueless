package com.queueless.dto;

import java.math.BigDecimal;

public class BranchSummaryResponse {
    private Long id;
    private Long businessId;
    private String businessName;
    private String name;
    private String branchName;
    private String category;
    private String address;
    private String phone;
    private Integer avgServiceDurationMinutes;
    private Boolean isPrimary;
    private String registrationStatus;
    private BigDecimal registrationFeePaid;
    private String publicQueueId;
    private boolean queueOpen;

    public BranchSummaryResponse() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBusinessId() {
        return businessId;
    }

    public void setBusinessId(Long businessId) {
        this.businessId = businessId;
    }

    public String getBusinessName() {
        return businessName;
    }

    public void setBusinessName(String businessName) {
        this.businessName = businessName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Integer getAvgServiceDurationMinutes() {
        return avgServiceDurationMinutes;
    }

    public void setAvgServiceDurationMinutes(Integer avgServiceDurationMinutes) {
        this.avgServiceDurationMinutes = avgServiceDurationMinutes;
    }

    public Boolean getIsPrimary() {
        return isPrimary;
    }

    public void setIsPrimary(Boolean isPrimary) {
        this.isPrimary = isPrimary;
    }

    public String getRegistrationStatus() {
        return registrationStatus;
    }

    public void setRegistrationStatus(String registrationStatus) {
        this.registrationStatus = registrationStatus;
    }

    public BigDecimal getRegistrationFeePaid() {
        return registrationFeePaid;
    }

    public void setRegistrationFeePaid(BigDecimal registrationFeePaid) {
        this.registrationFeePaid = registrationFeePaid;
    }

    public String getPublicQueueId() {
        return publicQueueId;
    }

    public void setPublicQueueId(String publicQueueId) {
        this.publicQueueId = publicQueueId;
    }

    public boolean isQueueOpen() {
        return queueOpen;
    }

    public void setQueueOpen(boolean queueOpen) {
        this.queueOpen = queueOpen;
    }
}
