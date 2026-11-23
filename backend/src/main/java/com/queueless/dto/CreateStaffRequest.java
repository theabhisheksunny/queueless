package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

public class CreateStaffRequest {

    @NotBlank(message = "Full name is required")
    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String fullName;

    @NotBlank(message = "Mobile number is required")
    private String phone;

    @NotBlank(message = "6-digit PIN is required")
    @Pattern(regexp = "^\\d{6}$", message = "PIN must be exactly 6 numeric digits")
    private String pin;

    private List<Long> branchIds = new ArrayList<>();

    public CreateStaffRequest() {}

    public CreateStaffRequest(String fullName, String phone, String pin, List<Long> branchIds) {
        this.fullName = fullName;
        this.phone = phone;
        this.pin = pin;
        this.branchIds = branchIds != null ? branchIds : new ArrayList<>();
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

    public String getPin() {
        return pin;
    }

    public void setPin(String pin) {
        this.pin = pin;
    }

    public List<Long> getBranchIds() {
        return branchIds;
    }

    public void setBranchIds(List<Long> branchIds) {
        this.branchIds = branchIds;
    }
}
