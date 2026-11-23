package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateBranchRequest {

    private Long businessId;

    @NotBlank(message = "Branch or shop name cannot be blank")
    @Size(max = 150, message = "Name must not exceed 150 characters")
    private String name;

    @Size(max = 150, message = "Branch name must not exceed 150 characters")
    private String branchName;

    @Size(max = 80, message = "Category must not exceed 80 characters")
    private String category;

    @Size(max = 255, message = "Address must not exceed 255 characters")
    private String address;

    @Size(max = 20, message = "Phone must not exceed 20 characters")
    private String phone;

    private Integer avgServiceDurationMinutes = 15;

    @Size(max = 128, message = "Idempotency key must not exceed 128 characters")
    private String idempotencyKey;

    public CreateBranchRequest() {}

    public CreateBranchRequest(Long businessId, String name, String branchName, String address, String phone, String category, Integer avgServiceDurationMinutes) {
        this.businessId = businessId;
        this.name = name;
        this.branchName = branchName;
        this.address = address;
        this.phone = phone;
        this.category = category;
        this.avgServiceDurationMinutes = avgServiceDurationMinutes;
    }

    public Long getBusinessId() {
        return businessId;
    }

    public void setBusinessId(Long businessId) {
        this.businessId = businessId;
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
