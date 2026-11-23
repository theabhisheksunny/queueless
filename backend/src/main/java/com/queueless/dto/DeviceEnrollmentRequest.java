package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class DeviceEnrollmentRequest {

    @NotBlank(message = "Mobile number is required.")
    private String phone;

    @NotBlank(message = "Temporary PIN is required.")
    @Pattern(regexp = "^\\d{6}$", message = "Temporary PIN must be exactly 6 digits.")
    private String temporaryPin;

    @NotBlank(message = "New permanent PIN is required.")
    @Pattern(regexp = "^\\d{6}$", message = "Permanent PIN must be exactly 6 digits.")
    private String newPermanentPin;

    @NotBlank(message = "Confirm permanent PIN is required.")
    @Pattern(regexp = "^\\d{6}$", message = "Confirm PIN must be exactly 6 digits.")
    private String confirmPermanentPin;

    @NotBlank(message = "Device registration ID is required.")
    private String deviceRegistrationId;

    @NotBlank(message = "Device name is required.")
    private String deviceName;

    @NotBlank(message = "Platform identifier is required.")
    private String platform;

    @NotBlank(message = "Device public key is required.")
    private String publicKey;

    public DeviceEnrollmentRequest() {}

    public DeviceEnrollmentRequest(String phone, String temporaryPin, String newPermanentPin,
                                   String confirmPermanentPin, String deviceRegistrationId,
                                   String deviceName, String platform, String publicKey) {
        this.phone = phone;
        this.temporaryPin = temporaryPin;
        this.newPermanentPin = newPermanentPin;
        this.confirmPermanentPin = confirmPermanentPin;
        this.deviceRegistrationId = deviceRegistrationId;
        this.deviceName = deviceName;
        this.platform = platform;
        this.publicKey = publicKey;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getTemporaryPin() {
        return temporaryPin;
    }

    public void setTemporaryPin(String temporaryPin) {
        this.temporaryPin = temporaryPin;
    }

    public String getNewPermanentPin() {
        return newPermanentPin;
    }

    public void setNewPermanentPin(String newPermanentPin) {
        this.newPermanentPin = newPermanentPin;
    }

    public String getConfirmPermanentPin() {
        return confirmPermanentPin;
    }

    public void setConfirmPermanentPin(String confirmPermanentPin) {
        this.confirmPermanentPin = confirmPermanentPin;
    }

    public String getDeviceRegistrationId() {
        return deviceRegistrationId;
    }

    public void setDeviceRegistrationId(String deviceRegistrationId) {
        this.deviceRegistrationId = deviceRegistrationId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }
}
