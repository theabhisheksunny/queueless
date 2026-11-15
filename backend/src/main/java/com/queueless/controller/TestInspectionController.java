package com.queueless.controller;

import com.queueless.service.PhoneNormalizationService;
import com.queueless.service.sms.SmsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Test Inspection Controller strictly intended for automated end-to-end testing environments.
 * Strictly disabled by default in production (requires app.testing.test-otp-endpoint=true).
 */
@RestController
@RequestMapping("/api/test")
@ConditionalOnProperty(name = "app.testing.test-otp-endpoint", havingValue = "true", matchIfMissing = false)
public class TestInspectionController {

    private final SmsService smsService;
    private final PhoneNormalizationService phoneNormalizationService;

    public TestInspectionController(SmsService smsService, PhoneNormalizationService phoneNormalizationService) {
        this.smsService = smsService;
        this.phoneNormalizationService = phoneNormalizationService;
    }

    @GetMapping("/last-otp")
    public ResponseEntity<Map<String, String>> getLastOtp(@RequestParam String phone) {
        String normalizedPhone = phoneNormalizationService.normalize(phone);
        String lastOtp = smsService.getLastSentOtp(normalizedPhone);
        if (lastOtp == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("phone", normalizedPhone, "otp", lastOtp));
    }
}
