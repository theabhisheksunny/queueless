package com.queueless.controller;

import com.queueless.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/owner/account")
public class AccountController {

    private final AuthService authService;

    public AccountController(AuthService authService) {
        this.authService = authService;
    }

    @DeleteMapping
    public ResponseEntity<Map<String, String>> deleteAccount(Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("Authentication required");
        }
        authService.deleteAccount(principal.getName());
        return ResponseEntity.ok(Map.of("message", "Account successfully deleted. Note that trial eligibility has been permanently recorded."));
    }
}
