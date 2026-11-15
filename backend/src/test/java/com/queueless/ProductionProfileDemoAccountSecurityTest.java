package com.queueless;

import com.queueless.dto.AuthRequest;
import com.queueless.dto.PinLoginRequest;
import com.queueless.repository.UserRepository;
import com.queueless.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.profiles.active=prod"
})
@ActiveProfiles("prod")
class ProductionProfileDemoAccountSecurityTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthService authService;

    @Test
    @DisplayName("Production Profile: Startup must NEVER seed demo account owner@queueless.com / password123")
    void testDemoAccountIsNotSeededInProduction() {
        assertTrue(userRepository.findByEmail("owner@queueless.com").isEmpty(),
                "CRITICAL SECURITY FAILURE: Demo account owner@queueless.com must never exist in production database!");
        assertTrue(userRepository.findByPhone("+919876543210").isEmpty(),
                "CRITICAL SECURITY FAILURE: Demo phone number +919876543210 must never exist in production database!");
    }

    @Test
    @DisplayName("Production Profile: Demo credentials cannot authenticate via email or phone")
    void testDemoCredentialsCannotAuthenticateInProduction() {
        // Attempt login with demo credentials
        assertThrows(BadCredentialsException.class, () ->
                authService.login(new AuthRequest("owner@queueless.com", "password123"))
        );

        // Attempt PIN login with demo mobile number
        assertThrows(BadCredentialsException.class, () ->
                authService.pinLogin(new PinLoginRequest("9876543210", "123456", "prod-test-fingerprint"), "127.0.0.1")
        );
    }
}
