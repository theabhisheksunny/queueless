package com.queueless;

import com.queueless.dto.RegisterRequest;
import com.queueless.service.OtpVerificationService;
import com.queueless.service.PhoneNormalizationService;
import com.queueless.service.sms.SmsService;
import org.springframework.stereotype.Component;

@Component
public class TestRegistrationHelper {

    private final OtpVerificationService otpVerificationService;
    private final SmsService smsService;
    private final PhoneNormalizationService phoneNormalizationService;

    public TestRegistrationHelper(OtpVerificationService otpVerificationService,
                                  SmsService smsService,
                                  PhoneNormalizationService phoneNormalizationService) {
        this.otpVerificationService = otpVerificationService;
        this.smsService = smsService;
        this.phoneNormalizationService = phoneNormalizationService;
    }

    public void prepareOtp(RegisterRequest request) {
        otpVerificationService.sendOtp(request.getPhone(), "127.0.0.1");
        String normalized = phoneNormalizationService.normalize(request.getPhone());
        request.setOtp(smsService.getLastSentOtp(normalized));
    }

    public String obtainOtp(String phone) {
        otpVerificationService.sendOtp(phone, "127.0.0.1");
        String normalized = phoneNormalizationService.normalize(phone);
        return smsService.getLastSentOtp(normalized);
    }
}
