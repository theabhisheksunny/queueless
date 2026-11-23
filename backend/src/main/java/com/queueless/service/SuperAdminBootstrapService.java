package com.queueless.service;

import com.queueless.entity.AuditLog;
import com.queueless.entity.User;
import com.queueless.entity.UserRole;
import com.queueless.repository.AuditLogRepository;
import com.queueless.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Controlled server-side bootstrap for the initial platform Super Administrator.
 * Guarantees that:
 * 1. Super Admin accounts can never be created via public registration.
 * 2. Initial Super Admin is established through secure server-side configuration or generation.
 * 3. Zero credentials, passwords, or PINs are hardcoded into source control or frontend code.
 * 4. An immutable audit record is logged upon bootstrap.
 * 5. Concurrent multi-instance bootstrap against the same empty DB safely handles unique constraint collisions.
 * 6. Repeated application restarts do not duplicate or overwrite existing Super Admin credentials.
 */
@Service
@Order(10)
public class SuperAdminBootstrapService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrapService.class);
    private static final String CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*";

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final PhoneNormalizationService phoneNormalizationService;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.superadmin.bootstrap.enabled:true}")
    private boolean bootstrapEnabled;

    @Value("${app.superadmin.bootstrap.email:superadmin@queueless.in}")
    private String bootstrapEmail;

    @Value("${app.superadmin.bootstrap.phone:+919999900000}")
    private String bootstrapPhone;

    @Value("${app.superadmin.bootstrap.password:}")
    private String bootstrapPassword;

    @Value("${app.superadmin.bootstrap.pin:}")
    private String bootstrapPin;

    public SuperAdminBootstrapService(UserRepository userRepository,
                                      AuditLogRepository auditLogRepository,
                                      PasswordEncoder passwordEncoder,
                                      PhoneNormalizationService phoneNormalizationService,
                                      PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
        this.phoneNormalizationService = phoneNormalizationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(String... args) {
        if (!bootstrapEnabled) {
            log.info("Super Admin server-side bootstrap is disabled by configuration.");
            return;
        }

        try {
            transactionTemplate.executeWithoutResult(status -> doBootstrap());
        } catch (DataIntegrityViolationException | ConcurrencyFailureException ex) {
            log.info("Concurrent Super Admin bootstrap collision detected. Another instance has initialized the Super Admin account ({}). Bootstrap completed safely.",
                    ex.getClass().getSimpleName());
        } catch (Exception ex) {
            log.warn("Super Admin bootstrap encountered non-fatal error: {}", ex.getMessage(), ex);
        }
    }

    private void doBootstrap() {
        String normalizedPhone = phoneNormalizationService.normalize(bootstrapPhone);
        String email = (bootstrapEmail != null && !bootstrapEmail.isBlank())
                ? bootstrapEmail.trim().toLowerCase()
                : "superadmin@queueless.in";

        long existingSuperAdminCount = userRepository.countByRole(UserRole.ROLE_SUPER_ADMIN);
        if (existingSuperAdminCount > 0) {
            log.info("Super Admin account already exists (count={}). Bootstrap step complete.", existingSuperAdminCount);
            return;
        }

        // Check if an account already exists with this phone or email
        User user = userRepository.findByEmail(email)
                .or(() -> userRepository.findByPhone(normalizedPhone))
                .orElse(null);

        String rawCredential;
        boolean wasGenerated = false;

        if (bootstrapPassword != null && !bootstrapPassword.isBlank()) {
            rawCredential = bootstrapPassword.trim();
        } else if (bootstrapPin != null && !bootstrapPin.isBlank()) {
            rawCredential = bootstrapPin.trim();
        } else {
            rawCredential = generateSecureCredential(16);
            wasGenerated = true;
        }

        if (user == null) {
            user = new User();
            user.setEmail(email);
            user.setPhone(normalizedPhone);
            user.setFullName("Platform Super Administrator");
        }

        user.setRole(UserRole.ROLE_SUPER_ADMIN);
        user.setPassword(passwordEncoder.encode(rawCredential));
        user.setStatus("ACTIVE");
        user.setTemporaryPin(false);
        user.setFailedPinAttempts(0);
        user.setCredentialsUpdatedAt(Instant.now());
        user = userRepository.saveAndFlush(user);

        // Record immutable append-only audit record
        AuditLog auditEntry = new AuditLog(
                "SUPER_ADMIN_BOOTSTRAP",
                "system-bootstrap",
                "USER",
                user.getId().toString(),
                "Initial platform Super Admin bootstrapped via controlled server process",
                "127.0.0.1"
        );
        auditLogRepository.saveAndFlush(auditEntry);

        if (wasGenerated) {
            log.info("SECURE BOOTSTRAP: Initial Super Admin account created [email={}, phone={}]. Generated secure initial credential: {}",
                    email, normalizedPhone, rawCredential);
        } else {
            log.info("SECURE BOOTSTRAP: Initial Super Admin account created [email={}, phone={}] from configured environment credentials.",
                    email, normalizedPhone);
        }
    }

    private String generateSecureCredential(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
}
