package com.queueless.config;

import com.queueless.exception.AccountLockedException;
import com.queueless.exception.DeviceMismatchException;
import com.queueless.exception.SubscriptionRequiredException;
import com.queueless.exception.TokenExpiredException;
import com.queueless.exception.TrialAlreadyConsumedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        String correlationId = java.util.UUID.randomUUID().toString();
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Validation Failed");
        body.put("message", "Validation failed: Please check the submitted fields and try again.");
        body.put("correlationId", correlationId);
        body.put("details", errors);

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return createErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleTokenExpired(TokenExpiredException ex) {
        String correlationId = java.util.UUID.randomUUID().toString();
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.GONE.value());
        body.put("error", ex.getMessage() != null ? ex.getMessage() : "Token Expired");
        body.put("code", "TOKEN_EXPIRED");
        body.put("message", ex.getMessage() != null ? ex.getMessage() : "Token Expired");
        body.put("correlationId", correlationId);
        return ResponseEntity.status(HttpStatus.GONE).body(body);
    }

    @ExceptionHandler(SubscriptionRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleSubscriptionRequired(SubscriptionRequiredException ex) {
        String correlationId = java.util.UUID.randomUUID().toString();
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.PAYMENT_REQUIRED.value());
        body.put("error", "Payment Required");
        body.put("code", "SUBSCRIPTION_REQUIRED");
        body.put("message", ex.getMessage());
        body.put("correlationId", correlationId);
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body);
    }

    @ExceptionHandler(TrialAlreadyConsumedException.class)
    public ResponseEntity<Map<String, Object>> handleTrialAlreadyConsumed(TrialAlreadyConsumedException ex) {
        String correlationId = java.util.UUID.randomUUID().toString();
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("error", "Trial Already Consumed");
        body.put("code", "TRIAL_ALREADY_CONSUMED");
        body.put("message", ex.getMessage());
        body.put("correlationId", correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String correlationId = java.util.UUID.randomUUID().toString();
        log.error("[CorrelationId: {}] Data integrity violation: {}", correlationId, ex.getMessage(), ex);
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (msg.contains("users_phone") || msg.contains("trial_eligibility") || msg.contains("idx_users_phone")) {
            return createErrorResponseWithId(HttpStatus.CONFLICT, "A registration or trial record already exists for this mobile identity.", correlationId, "TRIAL_CONFLICT");
        }
        if (msg.contains("payment_reference") || msg.contains("idx_payment_rec_ref")) {
            return createErrorResponseWithId(HttpStatus.CONFLICT, "This payment reference / UTR has already been recorded.", correlationId, "PAYMENT_CONFLICT");
        }
        if (msg.contains("uq_user_shop")) {
            return createErrorResponseWithId(HttpStatus.CONFLICT, "The staff member is already assigned to this branch.", correlationId, "MEMBERSHIP_CONFLICT");
        }
        return createErrorResponseWithId(HttpStatus.CONFLICT, "A database record conflict occurred. Please refresh and try again.", correlationId, "RECORD_CONFLICT");
    }

    @ExceptionHandler(DeviceMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleDeviceMismatch(DeviceMismatchException ex) {
        String correlationId = java.util.UUID.randomUUID().toString();
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.FORBIDDEN.value());
        body.put("error", ex.getMessage());
        body.put("code", "DEVICE_MISMATCH");
        body.put("message", ex.getMessage());
        body.put("correlationId", correlationId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return createErrorResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> handleAccountLocked(AccountLockedException ex) {
        String correlationId = java.util.UUID.randomUUID().toString();
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.LOCKED.value());
        body.put("error", ex.getMessage());
        body.put("code", "ACCOUNT_LOCKED");
        body.put("message", ex.getMessage());
        body.put("correlationId", correlationId);
        return ResponseEntity.status(HttpStatus.LOCKED).body(body);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return createErrorResponse(HttpStatus.UNAUTHORIZED, ex.getMessage() != null ? ex.getMessage() : "Incorrect mobile number or 6-digit PIN. Please verify and try again.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        String msg = ex.getMessage() != null && !ex.getMessage().isBlank()
                ? ex.getMessage()
                : "Access restricted: You do not have permission to access this resource.";
        return createErrorResponse(HttpStatus.FORBIDDEN, msg);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return createErrorResponse(HttpStatus.NOT_FOUND, "The requested resource was not found.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        String correlationId = java.util.UUID.randomUUID().toString();
        log.error("[CorrelationId: {}] Unhandled server exception: {}", correlationId, ex.getMessage(), ex);
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put("error", "Internal Server Error");
        body.put("message", "We're having trouble processing your request. Please try again.");
        body.put("correlationId", correlationId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<Map<String, Object>> createErrorResponse(HttpStatus status, String message) {
        return createErrorResponseWithId(status, message, java.util.UUID.randomUUID().toString(), null);
    }

    private ResponseEntity<Map<String, Object>> createErrorResponseWithId(HttpStatus status, String message, String correlationId) {
        return createErrorResponseWithId(status, message, correlationId, null);
    }

    private ResponseEntity<Map<String, Object>> createErrorResponseWithId(HttpStatus status, String message, String correlationId, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", message);
        body.put("message", message);
        if (code != null) {
            body.put("code", code);
        }
        body.put("correlationId", correlationId);
        return ResponseEntity.status(status).body(body);
    }
}
