package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.PinLoginRequest;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.User;
import com.queueless.repository.UserRepository;
import com.queueless.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminSecurityAndAuditTest {

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

    private static final String ADMIN_KEY = "admin-secret-queueless-2026";

    @Test
    @DisplayName("Admin PIN reset succeeds with valid admin key and updates user credentials")
    void testAdminResetPinSuccess() throws Exception {
        String phone = "9112233445";
        RegisterRequest registerReq = new RegisterRequest();
        registerReq.setPhone(phone);
        registerReq.setPin("112233");
        registerReq.setConfirmPin("112233");
        registerReq.setFullName("Admin Audit Owner");
        registerReq.setShopName("Admin Audit Shop");
        registerReq.setCategory("Retail Store");
        authService.register(registerReq);

        // Verify initial PIN authenticates
        PinLoginRequest initialLogin = new PinLoginRequest(phone, "112233");
        assertThat(authService.pinLogin(initialLogin, "127.0.0.1")).isNotNull();

        // Perform Admin PIN reset
        Map<String, String> resetBody = Map.of(
                "phone", phone,
                "newPin", "998877"
        );

        mockMvc.perform(post("/api/admin/reset-owner-pin")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Owner PIN successfully reset by administrator."));

        // Verify old PIN no longer authenticates
        PinLoginRequest oldLogin = new PinLoginRequest(phone, "112233");
        assertThrows(Exception.class, () -> authService.pinLogin(oldLogin, "127.0.0.1"));

        // Verify new PIN authenticates
        PinLoginRequest newLogin = new PinLoginRequest(phone, "998877");
        assertThat(authService.pinLogin(newLogin, "127.0.0.1")).isNotNull();

        // Verify DB entity state
        User user = userRepository.findByPhone("+919112233445").orElseThrow();
        assertThat(passwordEncoder.matches("998877", user.getPassword())).isTrue();
        assertThat(user.getFailedPinAttempts()).isEqualTo(0);
        assertThat(user.getPinLockedUntil()).isNull();
    }

    @Test
    @DisplayName("Admin PIN reset rejects unauthorized requests (missing or invalid X-Admin-Key)")
    void testAdminResetPinUnauthorized() throws Exception {
        Map<String, String> resetBody = Map.of(
                "phone", "9112233445",
                "newPin", "654321"
        );

        // Missing key
        mockMvc.perform(post("/api/admin/reset-owner-pin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetBody)))
                .andExpect(status().isForbidden());

        // Invalid key
        mockMvc.perform(post("/api/admin/reset-owner-pin")
                        .header("X-Admin-Key", "wrong-secret-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetBody)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin PIN reset rejects non-6-digit PIN format")
    void testAdminResetPinInvalidPinFormat() throws Exception {
        Map<String, String> shortPinBody = Map.of(
                "phone", "9112233445",
                "newPin", "123"
        );

        mockMvc.perform(post("/api/admin/reset-owner-pin")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(shortPinBody)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Admin PIN reset fails gracefully when phone does not exist")
    void testAdminResetPinPhoneNotFound() throws Exception {
        Map<String, String> nonExistentBody = Map.of(
                "phone", "9000000000",
                "newPin", "123456"
        );

        mockMvc.perform(post("/api/admin/reset-owner-pin")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nonExistentBody)))
                .andExpect(status().isBadRequest());
    }
}
