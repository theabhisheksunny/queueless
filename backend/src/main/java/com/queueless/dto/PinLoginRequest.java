package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class PinLoginRequest {

    @NotBlank(message = "Mobile number is required")
    private String phone;

    @NotBlank(message = "6-digit PIN is required")
    @Pattern(regexp = "^\\d{6}$", message = "PIN must be exactly 6 digits")
    private String pin;

    private String deviceFingerprint;

    public PinLoginRequest() {}

    public PinLoginRequest(String phone, String pin) {
        this.phone = phone;
        this.pin = pin;
    }

    public PinLoginRequest(String phone, String pin, String deviceFingerprint) {
        this.phone = phone;
        this.pin = pin;
        this.deviceFingerprint = deviceFingerprint;
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

    public String getDeviceFingerprint() {
        return deviceFingerprint;
    }

    public void setDeviceFingerprint(String deviceFingerprint) {
        this.deviceFingerprint = deviceFingerprint;
    }
}
