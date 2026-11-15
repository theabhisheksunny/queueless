package com.queueless.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class TrialAlreadyConsumedException extends RuntimeException {
    public TrialAlreadyConsumedException(String message) {
        super(message);
    }
}
