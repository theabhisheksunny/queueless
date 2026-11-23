package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class DeviceReplacementSubmitRequest {

    @NotBlank(message = "Mobile number is required.")
    private String phone;

    @NotBlank(message = "PIN is required.")
    @Pattern(regexp = "^\\d{6}$", message = "PIN must be exactly 6 digits.")
    private String pin;

    private String requestedDeviceRegistrationId;

    @NotBlank(message = "Requested device name is required.")
    private String requestedDeviceName;

    @NotBlank(message = "Requested device platform is required.")
    private String requestedDevicePlatform;

    @NotBlank(message = "Requested device public key is required.")
    private String requestedPublicKey;

    public DeviceReplacementSubmitRequest() {}

    public DeviceReplacementSubmitRequest(String phone, String pin, String requestedDeviceRegistrationId,
                                          String requestedDeviceName, String requestedDevicePlatform, String requestedPublicKey) {
        this.phone = phone;
        this.pin = pin;
        this.requestedDeviceRegistrationId = requestedDeviceRegistrationId;
        this.requestedDeviceName = requestedDeviceName;
        this.requestedDevicePlatform = requestedDevicePlatform;
        this.requestedPublicKey = requestedPublicKey;
    }

    public DeviceReplacementSubmitRequest(String phone, String pin, String requestedDeviceName,
                                          String requestedDevicePlatform, String requestedPublicKey) {
        this(phone, pin, null, requestedDeviceName, requestedDevicePlatform, requestedPublicKey);
    }

    public String getRequestedDeviceRegistrationId() {
        return requestedDeviceRegistrationId;
    }

    public void setRequestedDeviceRegistrationId(String requestedDeviceRegistrationId) {
        this.requestedDeviceRegistrationId = requestedDeviceRegistrationId;
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

    public String getRequestedDeviceName() {
        return requestedDeviceName;
    }

    public void setRequestedDeviceName(String requestedDeviceName) {
        this.requestedDeviceName = requestedDeviceName;
    }

    public String getRequestedDevicePlatform() {
        return requestedDevicePlatform;
    }

    public void setRequestedDevicePlatform(String requestedDevicePlatform) {
        this.requestedDevicePlatform = requestedDevicePlatform;
    }

    public String getRequestedPublicKey() {
        return requestedPublicKey;
    }

    public void setRequestedPublicKey(String requestedPublicKey) {
        this.requestedPublicKey = requestedPublicKey;
    }
}
