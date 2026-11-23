package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class DeviceReplacementCompleteRequest {

    @NotBlank(message = "Mobile number is required.")
    private String phone;

    @NotBlank(message = "PIN is required.")
    @Pattern(regexp = "^\\d{6}$", message = "PIN must be exactly 6 digits.")
    private String pin;

    @NotBlank(message = "Device registration ID is required.")
    private String deviceRegistrationId;

    @NotBlank(message = "Public key is required.")
    private String publicKey;

    public DeviceReplacementCompleteRequest() {}

    public DeviceReplacementCompleteRequest(String phone, String pin, String deviceRegistrationId, String publicKey) {
        this.phone = phone;
        this.pin = pin;
        this.deviceRegistrationId = deviceRegistrationId;
        this.publicKey = publicKey;
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

    public String getDeviceRegistrationId() {
        return deviceRegistrationId;
    }

    public void setDeviceRegistrationId(String deviceRegistrationId) {
        this.deviceRegistrationId = deviceRegistrationId;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }
}
