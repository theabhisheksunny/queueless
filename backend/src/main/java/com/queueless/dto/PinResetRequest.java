package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class PinResetRequest {

    @NotBlank(message = "Mobile number is required")
    private String phone;

    private String currentPin;

    @NotBlank(message = "New 6-digit PIN is required")
    @Pattern(regexp = "^\\d{6}$", message = "PIN must be exactly 6 digits")
    private String newPin;

    @NotBlank(message = "Confirm PIN is required")
    private String confirmPin;

    private String deviceFingerprint;

    public PinResetRequest() {}

    public PinResetRequest(String phone, String newPin, String confirmPin) {
        this.phone = phone;
        this.newPin = newPin;
        this.confirmPin = confirmPin;
    }

    public PinResetRequest(String phone, String currentPin, String newPin, String confirmPin) {
        this.phone = phone;
        this.currentPin = currentPin;
        this.newPin = newPin;
        this.confirmPin = confirmPin;
    }

    public PinResetRequest(String phone, String currentPin, String newPin, String confirmPin, String deviceFingerprint) {
        this.phone = phone;
        this.currentPin = currentPin;
        this.newPin = newPin;
        this.confirmPin = confirmPin;
        this.deviceFingerprint = deviceFingerprint;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getCurrentPin() {
        return currentPin;
    }

    public void setCurrentPin(String currentPin) {
        this.currentPin = currentPin;
    }

    public String getNewPin() {
        return newPin;
    }

    public void setNewPin(String newPin) {
        this.newPin = newPin;
    }

    public String getConfirmPin() {
        return confirmPin;
    }

    public void setConfirmPin(String confirmPin) {
        this.confirmPin = confirmPin;
    }

    public String getDeviceFingerprint() {
        return deviceFingerprint;
    }

    public void setDeviceFingerprint(String deviceFingerprint) {
        this.deviceFingerprint = deviceFingerprint;
    }
}
