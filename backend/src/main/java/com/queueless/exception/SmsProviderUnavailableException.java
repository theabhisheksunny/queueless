package com.queueless.exception;

public class SmsProviderUnavailableException extends RuntimeException {
    public SmsProviderUnavailableException(String message) {
        super(message);
    }
}
