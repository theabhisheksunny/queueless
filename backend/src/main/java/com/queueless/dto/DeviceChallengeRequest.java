package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;

public class DeviceChallengeRequest {

    @NotBlank(message = "Mobile number is required.")
    private String phone;

    @NotBlank(message = "Device registration ID is required.")
    private String deviceRegistrationId;

    public DeviceChallengeRequest() {}

    public DeviceChallengeRequest(String phone, String deviceRegistrationId) {
        this.phone = phone;
        this.deviceRegistrationId = deviceRegistrationId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getDeviceRegistrationId() {
        return deviceRegistrationId;
    }

    public void setDeviceRegistrationId(String deviceRegistrationId) {
        this.deviceRegistrationId = deviceRegistrationId;
    }
}
