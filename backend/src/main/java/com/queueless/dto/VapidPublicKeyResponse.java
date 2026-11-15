package com.queueless.dto;

public class VapidPublicKeyResponse {

    private String publicKey;

    public VapidPublicKeyResponse() {}

    public VapidPublicKeyResponse(String publicKey) {
        this.publicKey = publicKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }
}
