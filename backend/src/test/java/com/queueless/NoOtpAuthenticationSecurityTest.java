package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.AuthResponse;
import com.queueless.dto.PinLoginRequest;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.User;
import com.queueless.exception.TrialAlreadyConsumedException;
import com.queueless.repository.UserRepository;
import com.queueless.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class NoOtpAuthenticationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Clock mockClock;

    @BeforeEach
    void setUp() {
        mockClock = Clock.fixed(Instant.parse("2026-11-15T18:00:00Z"), ZoneId.of("UTC"));
        authService.setClock(mockClock);
    }

    @Test
    @DisplayName("1. Registration succeeds immediately without requiring any OTP")
    void testRegistrationSucceedsWithoutOtp() throws Exception {
        String phone = "9822334401";
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Kavita Patel");
        req.setShopName("Patel General Store");
        req.setCategory("Retail");
        req.setBusinessType("General Store");
        req.setPhone(phone);
        req.setPin("246810");
        req.setConfirmPin("246810");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.phone").value("+919822334401"))
                .andExpect(jsonPath("$.shopName").value("Patel General Store"))
                .andExpect(jsonPath("$.subscriptionActive").value(true))
                .andExpect(jsonPath("$.subscriptionDaysRemaining").value(7));
    }

    @Test
    @DisplayName("2. Obsolete OTP endpoints are completely eliminated from the application")
    void testObsoleteOtpEndpointsAreEliminated() throws Exception {
        // /api/auth/otp/send must not exist
        mockMvc.perform(post("/api/auth/otp/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"9822334402\"}"))
                .andExpect(status().isNotFound());

        // /api/auth/otp/verify must not exist
        mockMvc.perform(post("/api/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"9822334402\",\"otp\":\"123456\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("3. Duplicate mobile number registration is rejected")
    void testDuplicateMobileNumberRejected() {
        String phone = "9822334403";
        RegisterRequest req1 = new RegisterRequest();
        req1.setFullName("First Owner");
        req1.setShopName("First Shop");
        req1.setPhone(phone);
        req1.setPin("112233");
        req1.setConfirmPin("112233");
        authService.register(req1);

        RegisterRequest req2 = new RegisterRequest();
        req2.setFullName("Second Owner");
        req2.setShopName("Second Shop");
        req2.setPhone(phone);
        req2.setPin("445566");
        req2.setConfirmPin("445566");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                authService.register(req2)
        );
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @DisplayName("4. Invalid mobile numbers are rejected")
    void testInvalidMobileNumbersRejected() {
        RegisterRequest shortPhone = new RegisterRequest();
        shortPhone.setFullName("Short Phone");
        shortPhone.setShopName("Shop");
        shortPhone.setPhone("98765");
        shortPhone.setPin("123456");
        shortPhone.setConfirmPin("123456");

        assertThrows(IllegalArgumentException.class, () -> authService.register(shortPhone));
    }

    @Test
    @DisplayName("5. PIN must contain exactly six digits")
    void testPinMustContainSixDigits() {
        RegisterRequest shortPin = new RegisterRequest();
        shortPin.setFullName("User");
        shortPin.setShopName("Shop");
        shortPin.setPhone("9822334404");
        shortPin.setPin("12345"); // 5 digits
        shortPin.setConfirmPin("12345");

        assertThrows(IllegalArgumentException.class, () -> authService.register(shortPin));

        RegisterRequest alphaPin = new RegisterRequest();
        alphaPin.setFullName("User");
        alphaPin.setShopName("Shop");
        alphaPin.setPhone("9822334405");
        alphaPin.setPin("abcdef"); // 6 alpha
        alphaPin.setConfirmPin("abcdef");

        assertThrows(IllegalArgumentException.class, () -> authService.register(alphaPin));
    }

    @Test
    @DisplayName("6. Mismatched confirm PIN is rejected")
    void testMismatchedConfirmPinRejected() {
        RegisterRequest mismatch = new RegisterRequest();
        mismatch.setFullName("User");
        mismatch.setShopName("Shop");
        mismatch.setPhone("9822334406");
        mismatch.setPin("123456");
        mismatch.setConfirmPin("654321"); // mismatched

        assertThrows(IllegalArgumentException.class, () -> authService.register(mismatch));
    }

    @Test
    @DisplayName("7. PIN is securely hashed with BCrypt and never stored in plaintext")
    void testPinIsSecurelyHashed() {
        String phone = "9822334407";
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Security Check User");
        req.setShopName("Security Shop");
        req.setPhone(phone);
        req.setPin("987123");
        req.setConfirmPin("987123");
        authService.register(req);

        User user = userRepository.findByPhone("+919822334407").orElseThrow();
        assertNotEquals("987123", user.getPassword(), "Plaintext PIN must never be stored in database!");
        assertTrue(user.getPassword().startsWith("$2a$") || user.getPassword().startsWith("$2b$"),
                "PIN must be hashed with BCrypt!");
        assertTrue(passwordEncoder.matches("987123", user.getPassword()));
    }

    @Test
    @DisplayName("8. Routine login works using Mobile Number + 6-Digit PIN (No OTP)")
    void testRoutineLoginRequiresOnlyMobileAndPin() throws Exception {
        String phone = "9822334408";
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Routine Login User");
        req.setShopName("Routine Shop");
        req.setPhone(phone);
        req.setPin("334455");
        req.setConfirmPin("334455");
        authService.register(req);

        PinLoginRequest loginReq = new PinLoginRequest(phone, "334455", "chrome-mobile-device");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.phone").value("+919822334408"));
    }

    @Test
    @DisplayName("9. Account deletion and re-registration cannot bypass persistent trial ledger")
    void testTrialEligibilityPersistsAcrossAccountDeletion() {
        String phone = "9822334409";
        RegisterRequest req1 = new RegisterRequest();
        req1.setFullName("Trial User");
        req1.setShopName("First Shop");
        req1.setPhone(phone);
        req1.setPin("556677");
        req1.setConfirmPin("556677");
        authService.register(req1);

        // Delete account
        authService.deleteAccount("+91" + phone);

        // Re-registration must be blocked by the persistent ledger
        RegisterRequest req2 = new RegisterRequest();
        req2.setFullName("Re-registering Owner");
        req2.setShopName("Second Shop");
        req2.setPhone(phone);
        req2.setPin("998877");
        req2.setConfirmPin("998877");

        assertThrows(TrialAlreadyConsumedException.class, () -> authService.register(req2));
    }
}
