package com.queueless;

import com.queueless.dto.RegisterRequest;
import org.springframework.stereotype.Component;

@Component
public class TestRegistrationHelper {

    public void prepareOtp(RegisterRequest request) {
        if (request.getPin() == null || request.getPin().isBlank()) {
            request.setPin("123456");
            request.setConfirmPin("123456");
        }
    }
}
