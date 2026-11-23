package com.queueless.dto;

public class AuthRequest {

    private String email;
    private String password;
    private String phone;
    private String pin;
    private String deviceFingerprint;
    private String deviceRegistrationId;

    public AuthRequest() {}

    public AuthRequest(String email, String password) {
        this.email = email;
        this.password = password;
    }

    public AuthRequest(String phone, String pin, String deviceFingerprint) {
        this.phone = phone;
        this.pin = pin;
        this.deviceFingerprint = deviceFingerprint;
    }

    public AuthRequest(String phone, String pin, String deviceFingerprint, String deviceRegistrationId) {
        this.phone = phone;
        this.pin = pin;
        this.deviceFingerprint = deviceFingerprint;
        this.deviceRegistrationId = deviceRegistrationId;
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

    public String getDeviceRegistrationId() {
        return deviceRegistrationId;
    }

    public void setDeviceRegistrationId(String deviceRegistrationId) {
        this.deviceRegistrationId = deviceRegistrationId;
    }
}
