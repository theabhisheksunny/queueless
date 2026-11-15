package com.queueless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.OtpSendRequest;
import com.queueless.dto.PinLoginRequest;
import com.queueless.dto.RegisterRequest;
import com.queueless.exception.SmsProviderUnavailableException;
import com.queueless.exception.TrialAlreadyConsumedException;
import com.queueless.repository.UserRepository;
import com.queueless.service.AuthService;
import com.queueless.service.OtpVerificationService;
import com.queueless.service.PhoneNormalizationService;
import com.queueless.service.sms.SmsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OtpVerificationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private OtpVerificationService otpVerificationService;

    @Autowired
    private SmsServiceImpl smsService;

    @Autowired
    private PhoneNormalizationService phoneNormalizationService;

    @Autowired
    private UserRepository userRepository;

    private Clock mockClock;
    private Instant baseInstant;

    @BeforeEach
    void setUp() {
        baseInstant = Instant.parse("2026-11-15T12:00:00Z");
        mockClock = Clock.fixed(baseInstant, ZoneId.of("UTC"));
        otpVerificationService.setClock(mockClock);
        authService.setClock(mockClock);
        smsService.setProvider("mock");
        smsService.clearTestSentOtps();
    }

    @Test
    @DisplayName("1. Fixed or demo OTP (e.g. 123456) is strictly rejected during registration")
    void testFixedOrDemoOtpIsRejected() {
        String phone = "9822001101";
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        // Send OTP
        otpVerificationService.sendOtp(phone, "127.0.0.1");
        String dynamicOtp = smsService.getLastSentOtp(normalizedPhone);
        assertNotNull(dynamicOtp, "Dynamic OTP should be dispatched to SMS provider");

        // Attempt verification with hardcoded demo OTP "123456"
        if (!"123456".equals(dynamicOtp)) {
            assertThrows(IllegalArgumentException.class, () ->
                    otpVerificationService.verifyOtp(normalizedPhone, "123456")
            );

            // Attempt registration with "123456"
            RegisterRequest req = new RegisterRequest();
            req.setFullName("Demo Reject User");
            req.setShopName("Reject Shop");
            req.setCategory("Barber Shop");
            req.setPhone(phone);
            req.setOtp("123456");
            req.setPin("654321");
            req.setConfirmPin("654321");

            assertThrows(IllegalArgumentException.class, () -> authService.register(req));
        }
    }

    @Test
    @DisplayName("2. OTP value is NEVER exposed in API responses")
    void testOtpNeverExposedInApiResponse() throws Exception {
        String phone = "9822001102";
        OtpSendRequest request = new OtpSendRequest(phone);

        var result = mockMvc.perform(post("/api/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.cooldownSeconds").value(60))
                .andExpect(jsonPath("$.testOtp").doesNotExist())
                .andExpect(jsonPath("$.otp").doesNotExist())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);

        // Ensure only message and cooldownSeconds fields exist
        assertFalse(jsonNode.has("testOtp"));
        assertFalse(jsonNode.has("otp"));
        assertFalse(jsonNode.has("code"));

        String normalizedPhone = phoneNormalizationService.normalize(phone);
        String dynamicOtp = smsService.getLastSentOtp(normalizedPhone);
        assertNotNull(dynamicOtp);

        // Crucial: Raw response body string must not leak the dynamic OTP code
        assertFalse(responseBody.contains(dynamicOtp), "API response body must never contain the OTP code!");
    }

    @Test
    @DisplayName("3. Expired OTP (>5 minutes) is rejected")
    void testExpiredOtpIsRejected() {
        String phone = "9822001103";
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        otpVerificationService.sendOtp(phone, "127.0.0.1");
        String dynamicOtp = smsService.getLastSentOtp(normalizedPhone);

        // Advance clock by 5 minutes and 1 second (past 5m expiry)
        Instant expiredInstant = baseInstant.plusSeconds(301);
        otpVerificationService.setClock(Clock.fixed(expiredInstant, ZoneId.of("UTC")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                otpVerificationService.verifyOtp(normalizedPhone, dynamicOtp)
        );
        assertTrue(ex.getMessage().contains("OTP has expired"));
    }

    @Test
    @DisplayName("4. Incorrect OTP is rejected with remaining attempt count; 5th attempt locks")
    void testIncorrectOtpIsRejectedAndLocksAfterFiveAttempts() {
        String phone = "9822001104";
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        otpVerificationService.sendOtp(phone, "127.0.0.1");
        String dynamicOtp = smsService.getLastSentOtp(normalizedPhone);

        // Generate a code guaranteed not to equal dynamicOtp
        String wrongOtp = "999999".equals(dynamicOtp) ? "888888" : "999999";

        // Attempts 1 to 4 should report remaining attempts
        for (int i = 1; i <= 4; i++) {
            int remaining = 5 - i;
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    otpVerificationService.verifyOtp(normalizedPhone, wrongOtp)
            );
            assertTrue(ex.getMessage().contains(remaining + " attempt(s) remaining"));
        }

        // 5th failed attempt exhausts maximum attempts
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                otpVerificationService.verifyOtp(normalizedPhone, wrongOtp)
        );
        assertTrue(ex.getMessage().contains("Maximum verification attempts (5) exceeded"));

        // 6th attempt (even with CORRECT dynamic OTP) is rejected because record is locked
        assertThrows(IllegalStateException.class, () ->
                otpVerificationService.verifyOtp(normalizedPhone, dynamicOtp)
        );
    }

    @Test
    @DisplayName("5. Correct dynamically generated OTP succeeds for verification and registration")
    void testCorrectDynamicOtpSucceeds() {
        String phone = "9822001105";
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        otpVerificationService.sendOtp(phone, "127.0.0.1");
        String dynamicOtp = smsService.getLastSentOtp(normalizedPhone);
        assertNotNull(dynamicOtp);
        assertEquals(6, dynamicOtp.length());

        // Verify OTP
        boolean verified = otpVerificationService.verifyOtp(normalizedPhone, dynamicOtp);
        assertTrue(verified);

        // Register account using the correct dynamic OTP
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Rohan Dynamic");
        req.setShopName("Rohan Saloon");
        req.setCategory("Salon & Spa");
        req.setPhone(phone);
        req.setOtp(dynamicOtp);
        req.setPin("654321");
        req.setConfirmPin("654321");

        var authRes = authService.register(req);
        assertNotNull(authRes.getToken());
        assertEquals("Rohan Dynamic", authRes.getFullName());
    }

    @Test
    @DisplayName("6. Resend cooldown enforces 60-second waiting period between requests")
    void testResendCooldownEnforced() {
        String phone = "9822001106";

        // 1st request at T0
        var res1 = otpVerificationService.sendOtp(phone, "127.0.0.1");
        assertEquals(60, res1.getCooldownSeconds());

        // 2nd request at T0 + 15 seconds: blocked by cooldown
        otpVerificationService.setClock(Clock.fixed(baseInstant.plusSeconds(15), ZoneId.of("UTC")));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                otpVerificationService.sendOtp(phone, "127.0.0.1")
        );
        assertTrue(ex.getMessage().contains("Please wait 45 seconds"));

        // 3rd request at T0 + 65 seconds: cooldown has passed, request succeeds
        otpVerificationService.setClock(Clock.fixed(baseInstant.plusSeconds(65), ZoneId.of("UTC")));
        var res3 = otpVerificationService.sendOtp(phone, "127.0.0.1");
        assertEquals(60, res3.getCooldownSeconds());
    }

    @Test
    @DisplayName("7. If no SMS provider is configured, sendOtp throws 503 and registration cannot proceed")
    void testNoSmsProviderConfigured() throws Exception {
        smsService.setProvider("none");

        String phone = "9822001107";
        OtpSendRequest request = new OtpSendRequest(phone);

        // /api/auth/otp/send returns HTTP 503 Service Unavailable with clear message
        mockMvc.perform(post("/api/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SMS_PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SMS verification service is currently unavailable")));

        // Direct registration without OTP is rejected
        RegisterRequest regReq = new RegisterRequest();
        regReq.setFullName("No SMS User");
        regReq.setShopName("No SMS Shop");
        regReq.setPhone(phone);
        regReq.setOtp("123456");
        regReq.setPin("112233");
        regReq.setConfirmPin("112233");

        assertThrows(IllegalArgumentException.class, () -> authService.register(regReq));
    }

    @Test
    @DisplayName("8. Routine login requires ONLY mobile number + 6-digit PIN (no OTP)")
    void testRoutineLoginRequiresOnlyPhoneAndPin() throws Exception {
        String phone = "9822001108";
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        // Register with dynamic OTP
        otpVerificationService.sendOtp(phone, "127.0.0.1");
        String dynamicOtp = smsService.getLastSentOtp(normalizedPhone);

        RegisterRequest req = new RegisterRequest();
        req.setFullName("Routine Login User");
        req.setShopName("Routine Shop");
        req.setCategory("Barber Shop");
        req.setPhone(phone);
        req.setOtp(dynamicOtp);
        req.setPin("789123");
        req.setConfirmPin("789123");
        authService.register(req);

        // Routine Login with phone + PIN: NO OTP field supplied or required
        PinLoginRequest loginReq = new PinLoginRequest(phone, "789123");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.fullName").value("Routine Login User"));
    }

    @Test
    @DisplayName("9. Trial eligibility protections remain intact across registration and deletion")
    void testTrialEligibilityProtections() {
        String phone = "9822001109";
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        // Register initial account
        otpVerificationService.sendOtp(phone, "127.0.0.1");
        String otp1 = smsService.getLastSentOtp(normalizedPhone);

        RegisterRequest req1 = new RegisterRequest();
        req1.setFullName("Trial User");
        req1.setShopName("Trial Shop");
        req1.setCategory("Repair Shop");
        req1.setPhone(phone);
        req1.setOtp(otp1);
        req1.setPin("334455");
        req1.setConfirmPin("334455");
        var authRes = authService.register(req1);
        assertTrue(authRes.isSubscriptionActive());
        assertEquals(7, authRes.getSubscriptionDaysRemaining());

        // Fast forward 1 minute and try to register same phone again -> rejected
        otpVerificationService.setClock(Clock.fixed(baseInstant.plusSeconds(70), ZoneId.of("UTC")));
        otpVerificationService.sendOtp(phone, "127.0.0.1");
        String otp2 = smsService.getLastSentOtp(normalizedPhone);

        RegisterRequest reqDuplicate = new RegisterRequest();
        reqDuplicate.setFullName("Duplicate Phone User");
        reqDuplicate.setShopName("Another Shop");
        reqDuplicate.setPhone(phone);
        reqDuplicate.setOtp(otp2);
        reqDuplicate.setPin("111111");
        reqDuplicate.setConfirmPin("111111");

        assertThrows(IllegalArgumentException.class, () -> authService.register(reqDuplicate));

        // Delete account
        authService.deleteAccount(authRes.getEmail());

        // Fast forward and try to re-register after deletion -> TrialAlreadyConsumedException!
        otpVerificationService.setClock(Clock.fixed(baseInstant.plusSeconds(140), ZoneId.of("UTC")));
        otpVerificationService.sendOtp(phone, "127.0.0.1");
        String otp3 = smsService.getLastSentOtp(normalizedPhone);

        RegisterRequest reqReRegister = new RegisterRequest();
        reqReRegister.setFullName("Re-Register Attempt");
        reqReRegister.setShopName("New Shop");
        reqReRegister.setPhone(phone);
        reqReRegister.setOtp(otp3);
        reqReRegister.setPin("222222");
        reqReRegister.setConfirmPin("222222");

        assertThrows(TrialAlreadyConsumedException.class, () -> authService.register(reqReRegister));
    }
}
