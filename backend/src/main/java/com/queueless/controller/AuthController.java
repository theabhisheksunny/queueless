package com.queueless.controller;

import com.queueless.dto.*;
import com.queueless.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final com.queueless.service.DeviceAuthService deviceAuthService;
    private final com.queueless.security.JwtTokenProvider tokenProvider;

    public AuthController(AuthService authService,
                          com.queueless.service.DeviceAuthService deviceAuthService,
                          com.queueless.security.JwtTokenProvider tokenProvider) {
        this.authService = authService;
        this.deviceAuthService = deviceAuthService;
        this.tokenProvider = tokenProvider;
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
            PinLoginRequest pinReq = new PinLoginRequest(
                    request.getPhone(),
                    pin,
                    request.getDeviceFingerprint(),
                    request.getDeviceRegistrationId()
            );
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

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully. You can now log in."));
    }

    @PostMapping({"/device-enroll", "/enroll-first-device"})
    public ResponseEntity<AuthResponse> enrollFirstDevice(
            @Valid @RequestBody DeviceEnrollmentRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        AuthResponse response = deviceAuthService.enrollFirstDevice(request, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/device-login")
    public ResponseEntity<AuthResponse> deviceLogin(
            @Valid @RequestBody DeviceProofLoginRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        AuthResponse response = deviceAuthService.deviceLogin(request, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/device-replacement/request")
    public ResponseEntity<DeviceReplacementResponse> requestDeviceReplacement(
            @Valid @RequestBody DeviceReplacementSubmitRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        DeviceReplacementResponse response = deviceAuthService.requestDeviceReplacement(request, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping({"/device-challenge", "/device/challenge"})
    public ResponseEntity<DeviceChallengeResponse> createDeviceChallenge(
            @Valid @RequestBody DeviceChallengeRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        DeviceChallengeResponse response = deviceAuthService.createChallenge(request, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping({"/device-replacement/complete", "/device/complete-replacement"})
    public ResponseEntity<AuthResponse> completeDeviceReplacement(
            @Valid @RequestBody DeviceReplacementCompleteRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        AuthResponse response = deviceAuthService.completeDeviceReplacement(request, clientIp);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest httpRequest) {
        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                String username = tokenProvider.getUsernameFromToken(token);
                String sessionId = tokenProvider.getSessionIdFromToken(token);
                deviceAuthService.logout(username, sessionId);
            } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully. Device enrollment preserved."));
    }
}
