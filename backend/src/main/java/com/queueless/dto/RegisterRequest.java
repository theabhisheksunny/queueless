package com.queueless.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RegisterRequest {

    @NotBlank(message = "Your name is required")
    private String fullName;

    private String email;

    private String password;

    private String pin;

    private String confirmPin;

    @NotBlank(message = "Mobile number is required")
    private String phone;

    @NotBlank(message = "OTP is required")
    private String otp;

    private String deviceFingerprint;

    @NotBlank(message = "Shop name is required")
    private String shopName;

    private String category = "General";

    private Integer avgServiceDurationMinutes = 15;

    public RegisterRequest() {}

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
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

    public String getPin() {
        return pin;
    }

    public void setPin(String pin) {
        this.pin = pin;
    }

    public String getConfirmPin() {
        return confirmPin;
    }

    public void setConfirmPin(String confirmPin) {
        this.confirmPin = confirmPin;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getOtp() {
        return otp;
    }

    public void setOtp(String otp) {
        this.otp = otp;
    }

    public String getDeviceFingerprint() {
        return deviceFingerprint;
    }

    public void setDeviceFingerprint(String deviceFingerprint) {
        this.deviceFingerprint = deviceFingerprint;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getAvgServiceDurationMinutes() {
        return avgServiceDurationMinutes;
    }

    public void setAvgServiceDurationMinutes(Integer avgServiceDurationMinutes) {
        this.avgServiceDurationMinutes = avgServiceDurationMinutes;
    }
}
