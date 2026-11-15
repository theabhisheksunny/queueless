package com.queueless.controller;

import com.queueless.dto.*;
import com.queueless.service.AuthService;
import com.queueless.service.OtpVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final OtpVerificationService otpVerificationService;

    public AuthController(AuthService authService, OtpVerificationService otpVerificationService) {
        this.authService = authService;
        this.otpVerificationService = otpVerificationService;
    }

    private String extractClientIp(HttpServletRequest request) {
        if (request == null) return "127.0.0.1";
        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = request.getRemoteAddr();
        } else if (clientIp.contains(",")) {
            clientIp = clientIp.split(",")[0].trim();
        }
        return clientIp;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);

        // If phone is provided, route through PIN authentication
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            String pin = request.getPin() != null && !request.getPin().isBlank() 
                    ? request.getPin() 
                    : request.getPassword();
            PinLoginRequest pinReq = new PinLoginRequest(request.getPhone(), pin, request.getDeviceFingerprint());
            return ResponseEntity.ok(authService.pinLogin(pinReq, clientIp));
        }

        // Routine / legacy login via email or username identifier
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            return ResponseEntity.ok(authService.login(request));
        }

        throw new IllegalArgumentException("Mobile number and 6-digit PIN are required.");
    }

    @PostMapping("/pin-login")
    public ResponseEntity<AuthResponse> pinLogin(@Valid @RequestBody PinLoginRequest request, HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        AuthResponse response = authService.pinLogin(request, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reset-pin")
    public ResponseEntity<Map<String, String>> resetPin(@Valid @RequestBody PinResetRequest request) {
        authService.resetPin(request);
        return ResponseEntity.ok(Map.of("message", "PIN reset successfully. Active sessions revoked. You can now log in."));
    }

    @PostMapping("/otp/send")
    public ResponseEntity<OtpSendResponse> sendOtp(@Valid @RequestBody OtpSendRequest request, HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        OtpSendResponse response = otpVerificationService.sendOtp(request.getPhone(), clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<Map<String, Object>> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        boolean verified = otpVerificationService.verifyOtp(request.getPhone(), request.getOtp());
        return ResponseEntity.ok(Map.of("verified", verified, "message", "OTP verified successfully."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully. You can now log in."));
    }
}
