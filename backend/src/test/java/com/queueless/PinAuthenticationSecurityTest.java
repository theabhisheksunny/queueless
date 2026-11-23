package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.PinLoginRequest;
import com.queueless.dto.PinResetRequest;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.User;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PinAuthenticationSecurityTest {

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
        mockClock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneId.of("UTC"));
        authService.setClock(mockClock);
    }

    @Test
    @DisplayName("Simplified Registration & Routine Login: Mobile + 6-digit PIN without any SMS OTP")
    void testSimplifiedRegistrationAndRoutineLogin() throws Exception {
        String phone = "9812345601";
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Ramesh Sharma");
        req.setShopName("Sharma Medicals");
        req.setCategory("Pharmacy");
        req.setPhone(phone);
        req.setPin("654321");
        req.setConfirmPin("654321");
        req.setDeviceFingerprint("Android-Chrome-Fingerprint-1");

        // Registration succeeds immediately without requiring OTP
        var regResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.phone").value("+919812345601"))
                .andExpect(jsonPath("$.shopName").value("Sharma Medicals"))
                .andReturn();

        // Verify PIN is securely hashed via BCrypt in the database (never stored plaintext)
        User user = userRepository.findByPhone("+919812345601").orElseThrow();
        assertNotEquals("654321", user.getPassword(), "PIN must NEVER be stored in plaintext!");
        assertTrue(passwordEncoder.matches("654321", user.getPassword()), "BCrypt hash must verify against the PIN");

        // Routine Login with mobile + PIN (No OTP required)
        PinLoginRequest loginReq = new PinLoginRequest(phone, "654321", "Android-Chrome-Fingerprint-1");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.phone").value("+919812345601"));
    }

    @Test
    @DisplayName("Brute-Force Lockout: 5 failed PIN attempts trigger a 15-minute account lockout")
    void testBruteForceLockoutProtection() throws Exception {
        String phone = "9812345602";
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Anand Verma");
        req.setShopName("Verma Auto Repairs");
        req.setCategory("Automobile Service");
        req.setPhone(phone);
        req.setPin("112233");
        req.setConfirmPin("112233");
        authService.register(req);

        PinLoginRequest wrongPin = new PinLoginRequest(phone, "999999");

        // Attempts 1 to 4 should fail with attempt warning
        for (int i = 1; i <= 4; i++) {
            int remaining = 5 - i;
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(wrongPin)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(remaining + " attempt(s) remaining")));
        }

        // 5th attempt triggers lockout
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongPin)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Account locked for 15 minutes")));

        // 6th attempt (even with CORRECT PIN) is immediately rejected due to active lockout
        PinLoginRequest correctPin = new PinLoginRequest(phone, "112233");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctPin)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Account temporarily locked")));
    }

    @Test
    @DisplayName("Session revocation: Resetting 6-digit PIN immediately invalidates previous JWT tokens on other devices")
    void testSessionRevocationOnPinReset() throws Exception {
        String phone = "9812345603";
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Sunita Clinic");
        req.setShopName("Sunita Homoeopathy Clinic");
        req.setCategory("Clinic & Doctor");
        req.setPhone(phone);
        req.setPin("445566");
        req.setConfirmPin("445566");
        var authRes = authService.register(req);
        String oldToken = authRes.getToken();

        // Verify old token works
        mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value("Sunita Homoeopathy Clinic"));

        // Fast forward clock 2 minutes and reset PIN using current PIN
        Instant resetTime = Instant.parse("2026-11-15T10:02:00Z");
        authService.setClock(Clock.fixed(resetTime, ZoneId.of("UTC")));

        PinResetRequest resetReq = new PinResetRequest(phone, "445566", "778899", "778899");
        mockMvc.perform(post("/api/auth/reset-pin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PIN reset successfully")));

        // OLD TOKEN is now REVOKED! Access to dashboard must be denied (unauthenticated/unauthorized)
        mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());

        // Login with new PIN succeeds and grants new active token
        PinLoginRequest newLoginReq = new PinLoginRequest(phone, "778899");
        var newLoginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newLoginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andReturn();

        String newToken = objectMapper.readTree(newLoginRes.getResponse().getContentAsString()).get("token").asText();
        assertNotEquals(oldToken, newToken);

        mockMvc.perform(get("/api/queue/dashboard")
                        .header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PIN reset preserves trial history and does not reset 7-day trial eligibility")
    void testPinResetPreservesTrialWithoutResettingEligibility() {
        String phone = "9812345604";
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Dinesh Sharma");
        req.setShopName("Dinesh Electronics");
        req.setCategory("Repair Shop");
        req.setPhone(phone);
        req.setPin("123123");
        req.setConfirmPin("123123");
        authService.register(req);

        // Advance clock by 3 days
        Instant threeDaysLater = Instant.parse("2026-11-18T10:00:00Z");
        authService.setClock(Clock.fixed(threeDaysLater, ZoneId.of("UTC")));

        // Reset PIN using current PIN
        PinResetRequest resetReq = new PinResetRequest(phone, "123123", "987987", "987987");
        authService.resetPin(resetReq);

        // Check login days remaining: should be 4 days (7 - 3 = 4), NOT 7!
        PinLoginRequest loginReq = new PinLoginRequest(phone, "987987");
        var postResetAuth = authService.pinLogin(loginReq, "127.0.0.1");
        assertEquals(4, postResetAuth.getSubscriptionDaysRemaining());
        assertTrue(postResetAuth.isSubscriptionActive());
    }

    @Test
    @DisplayName("PIN Validation: Rejects PINs that are not exactly 6 numeric digits")
    void testPinFormatValidation() {
        RegisterRequest shortPin = new RegisterRequest();
        shortPin.setFullName("Short PIN");
        shortPin.setShopName("Short Shop");
        shortPin.setPhone("9812345605");
        shortPin.setPin("1234"); // 4 digits
        shortPin.setConfirmPin("1234");

        assertThrows(IllegalArgumentException.class, () -> authService.register(shortPin));

        RegisterRequest nonNumericPin = new RegisterRequest();
        nonNumericPin.setFullName("Alpha PIN");
        nonNumericPin.setShopName("Alpha Shop");
        nonNumericPin.setPhone("9812345606");
        nonNumericPin.setPin("abcdef"); // non-numeric
        nonNumericPin.setConfirmPin("abcdef");

        assertThrows(IllegalArgumentException.class, () -> authService.register(nonNumericPin));
    }
}
