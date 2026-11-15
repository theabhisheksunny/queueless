package com.queueless.service.push;

public class PushSendResult {

    private final boolean success;
    private final boolean expired;
    private final int statusCode;
    private final String message;

    public PushSendResult(boolean success, boolean expired, int statusCode, String message) {
        this.success = success;
        this.expired = expired;
        this.statusCode = statusCode;
        this.message = message;
    }

    public static PushSendResult success(int statusCode) {
        return new PushSendResult(true, false, statusCode, "Delivered");
    }

    public static PushSendResult expired(int statusCode, String message) {
        return new PushSendResult(false, true, statusCode, message);
    }

    public static PushSendResult failure(int statusCode, String message) {
        return new PushSendResult(false, false, statusCode, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isExpired() {
        return expired;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getMessage() {
        return message;
    }
}
