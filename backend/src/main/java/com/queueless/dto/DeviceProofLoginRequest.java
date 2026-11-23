package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class DeviceProofLoginRequest {

    @NotBlank(message = "Mobile number is required.")
    private String phone;

    @NotBlank(message = "PIN is required.")
    @Pattern(regexp = "^\\d{6}$", message = "PIN must be exactly 6 digits.")
    private String pin;

    private String deviceRegistrationId;

    private Long clientTimestamp;

    private String challenge;

    private String signature;

    private String deviceFingerprint;

    public DeviceProofLoginRequest() {}

    public DeviceProofLoginRequest(String phone, String pin, String deviceRegistrationId,
                                   String challenge, String signature) {
        this.phone = phone;
        this.pin = pin;
        this.deviceRegistrationId = deviceRegistrationId;
        this.challenge = challenge;
        this.signature = signature;
    }

    public DeviceProofLoginRequest(String phone, String pin, String deviceRegistrationId,
                                   Long clientTimestamp, String signature) {
        this.phone = phone;
        this.pin = pin;
        this.deviceRegistrationId = deviceRegistrationId;
        this.clientTimestamp = clientTimestamp;
        this.signature = signature;
    }

    public String getChallenge() {
        return challenge;
    }

    public void setChallenge(String challenge) {
        this.challenge = challenge;
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

    public Long getClientTimestamp() {
        return clientTimestamp;
    }

    public void setClientTimestamp(Long clientTimestamp) {
        this.clientTimestamp = clientTimestamp;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getDeviceFingerprint() {
        return deviceFingerprint;
    }

    public void setDeviceFingerprint(String deviceFingerprint) {
        this.deviceFingerprint = deviceFingerprint;
    }
}
