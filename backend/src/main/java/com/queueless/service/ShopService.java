package com.queueless.service;

import com.queueless.dto.ShopSettingsRequest;
import com.queueless.entity.Shop;
import com.queueless.repository.ShopRepository;
import com.queueless.util.PublicMerchantCodeGenerator;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Objects;

@Service
public class ShopService {

    public static final int MAX_REGISTRATION_RETRIES = 3;
    public static final String PUBLIC_MERCHANT_CODE_CONSTRAINT = "idx_shops_public_merchant_code";

    private final ShopRepository shopRepository;
    private final PlatformTransactionManager transactionManager;

    public ShopService(ShopRepository shopRepository, PlatformTransactionManager transactionManager) {
        this.shopRepository = Objects.requireNonNull(shopRepository, "shopRepository must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
    }

    @Transactional(readOnly = true)
    public Shop getShop(Long shopId) {
        return shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + shopId));
    }

    @Transactional
    public Shop updateShopSettings(Long shopId, ShopSettingsRequest request) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + shopId));

        if (request.getName() != null && !request.getName().isBlank()) {
            shop.setName(request.getName().trim());
        }
        // Business category is permanent and locked after registration
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            String trimmedCategory = request.getCategory().trim();
            if (!trimmedCategory.equalsIgnoreCase(shop.getCategory())) {
                throw new IllegalArgumentException("Business category is locked after registration. To change it, submit a category change request with the ₹101 modification fee.");
            }
        }
        if (request.getAvgServiceDurationMinutes() != null && request.getAvgServiceDurationMinutes() > 0) {
            shop.setAvgServiceDurationMinutes(request.getAvgServiceDurationMinutes());
        }

        return shopRepository.save(shop);
    }

    /**
     * Generates a cryptographically secure public merchant code guaranteed unique
     * against committed database records via pre-insert collision verification.
     */
    public String generateUniquePublicMerchantCode() {
        int retries = 0;
        String code;
        do {
            code = PublicMerchantCodeGenerator.generate();
            retries++;
            if (retries > 10) {
                throw new IllegalStateException("Excessive collisions encountered generating unique public merchant code");
            }
        } while (shopRepository.existsByPublicMerchantCode(code));
        return code;
    }

    /**
     * Determines whether an exception represents a unique constraint violation on public_merchant_code.
     * Identifies PostgreSQL and H2 SQLSTATE 23505 and verifies the constraint/index name
     * ('idx_shops_public_merchant_code' or 'public_merchant_code').
     * Foreign key (23503), NOT NULL (23502), and other column violations return false.
     */
    public static boolean isPublicMerchantCodeUniqueViolation(Throwable ex) {
        if (ex == null) {
            return false;
        }

        Throwable curr = ex;
        while (curr != null) {
            // Check Hibernate ConstraintViolationException
            if (curr instanceof ConstraintViolationException cve) {
                String sqlState = cve.getSQLState();
                String constraintName = cve.getConstraintName();
                if ("23505".equals(sqlState)) {
                    if (constraintName != null &&
                        (constraintName.toLowerCase().contains("idx_shops_public_merchant_code") ||
                         constraintName.toLowerCase().contains("public_merchant_code"))) {
                        return true;
                    }
                }
            }

            // Check JDBC SQLException
            if (curr instanceof SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                String msg = sqlEx.getMessage();
                if ("23505".equals(sqlState)) {
                    if (msg != null &&
                        (msg.toLowerCase().contains("idx_shops_public_merchant_code") ||
                         msg.toLowerCase().contains("public_merchant_code"))) {
                        return true;
                    }
                }
            }

            // Check exception message text for SQLSTATE 23505 or unique constraint identifier
            String msg = curr.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                boolean isUnique = lower.contains("23505") || lower.contains("unique") || lower.contains("duplicate");
                boolean isMerchantCode = lower.contains("idx_shops_public_merchant_code") || lower.contains("public_merchant_code");
                boolean isOtherConstraint = lower.contains("23503") || lower.contains("foreign key") ||
                                            lower.contains("23502") || lower.contains("not-null") || lower.contains("null value");
                if (isUnique && isMerchantCode && !isOtherConstraint) {
                    return true;
                }
            }

            curr = curr.getCause();
        }

        return false;
    }

    /**
     * Persists a shop ensuring an opaque public merchant code is populated with transaction-safe
     * collision retry.
     *
     * Transaction Safety & Constraint-Specific Guarantees:
     * 1. Retries ONLY when the 'idx_shops_public_merchant_code' unique constraint is violated (SQLSTATE 23505).
     * 2. Non-retryable integrity errors (foreign keys 23503, NOT NULL 23502) fail immediately.
     * 3. Each attempt executes in an isolated, fresh transaction (PROPAGATION_REQUIRES_NEW).
     * 4. Flushes within each attempt (saveAndFlush) so uniqueness failures trigger rollback of only that attempt.
     * 5. An outer transaction is never marked rollback-only if an individual attempt encounters a collision.
     * 6. Bounded at MAX_REGISTRATION_RETRIES (3 attempts), failing clearly and preserving the root cause.
     */
    public Shop saveShopWithUniqueCodeRetry(Shop shop) {
        return saveShopWithUniqueCodeRetry(shop, MAX_REGISTRATION_RETRIES);
    }

    public Shop saveShopWithUniqueCodeRetry(Shop shop, int maxAttempts) {
        Throwable lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (shop.getPublicMerchantCode() == null || shop.getPublicMerchantCode().isBlank()) {
                shop.setPublicMerchantCode(generateUniquePublicMerchantCode());
            }

            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            try {
                return txTemplate.execute(status -> {
                    return shopRepository.saveAndFlush(shop);
                });
            } catch (Exception ex) {
                // Determine whether this error is a retryable public_merchant_code unique constraint collision
                if (isPublicMerchantCodeUniqueViolation(ex)) {
                    lastException = ex;
                    // Reset entity ID and code to allow fresh insert on retry
                    shop.setId(null);
                    shop.setPublicMerchantCode(null);
                } else {
                    // Non-retryable error (foreign key, NOT NULL, or other constraint): fail immediately!
                    if (ex instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new RuntimeException("Non-retryable persistence failure in saveShopWithUniqueCodeRetry", ex);
                }
            }
        }

        throw new IllegalStateException(
                "Failed to persist shop with unique public merchant code after " + maxAttempts + " attempts",
                lastException
        );
    }
}
