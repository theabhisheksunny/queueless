package com.queueless;

import com.queueless.dto.RegisterRequest;
import org.springframework.stereotype.Component;

@Component
public class TestRegistrationHelper {

    /**
     * Prepares default 6-digit test PIN for registration requests.
     */
    public void preparePin(RegisterRequest request) {
        if (request.getPin() == null || request.getPin().isBlank()) {
            request.setPin("123456");
            request.setConfirmPin("123456");
        }
    }
}
