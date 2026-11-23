package com.queueless;

import com.queueless.entity.ApplicationSetting;
import com.queueless.entity.Shop;
import com.queueless.entity.Subscription;
import com.queueless.entity.SubscriptionPlan;
import com.queueless.entity.SubscriptionStatus;
import com.queueless.entity.TrialEligibilityLedger;
import com.queueless.entity.User;
import com.queueless.entity.UserRole;
import com.queueless.repository.ApplicationSettingRepository;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.SubscriptionRepository;
import com.queueless.repository.TrialEligibilityLedgerRepository;
import com.queueless.repository.UserRepository;
import com.queueless.service.PhoneNormalizationService;
import com.queueless.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

@SpringBootApplication
@EnableAsync
@org.springframework.scheduling.annotation.EnableScheduling
public class QueueLessApplication {

    private static final Logger log = LoggerFactory.getLogger(QueueLessApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(QueueLessApplication.class, args);
    }

    /**
     * Seeds essential application pricing and UPI settings if missing.
     * Safe across all environments (production and development).
     */
    @Bean
    public CommandLineRunner initApplicationSettings(ApplicationSettingRepository settingRepo) {
        return args -> {
            try {
                settingRepo.findBySettingKey(SubscriptionService.KEY_UPI_ID).ifPresentOrElse(
                        setting -> {
                            if ("queueless@upi".equals(setting.getSettingValue())) {
                                setting.setSettingValue(SubscriptionService.DEFAULT_UPI_ID);
                                settingRepo.save(setting);
                            }
                        },
                        () -> settingRepo.save(new ApplicationSetting(
                                SubscriptionService.KEY_UPI_ID,
                                SubscriptionService.DEFAULT_UPI_ID,
                                "Subscription UPI ID for direct payment"
                        ))
                );
            } catch (DataIntegrityViolationException ex) {
                log.info("Setting KEY_UPI_ID initialized concurrently by another instance.");
            }

            saveDefaultSettingIfAbsent(settingRepo, SubscriptionService.KEY_UPI_NAME,
                    SubscriptionService.DEFAULT_UPI_NAME, "Subscription UPI Merchant/Payee Name");
            saveDefaultSettingIfAbsent(settingRepo, SubscriptionService.KEY_MONTHLY_PRICE,
                    SubscriptionService.DEFAULT_MONTHLY_PRICE.toString(), "Monthly plan price in INR");
            saveDefaultSettingIfAbsent(settingRepo, SubscriptionService.KEY_YEARLY_PRICE,
                    SubscriptionService.DEFAULT_YEARLY_PRICE.toString(), "Yearly plan price in INR");
        };
    }

    private void saveDefaultSettingIfAbsent(ApplicationSettingRepository repo, String key, String defaultValue, String description) {
        try {
            if (repo.findBySettingKey(key).isEmpty()) {
                repo.save(new ApplicationSetting(key, defaultValue, description));
            }
        } catch (DataIntegrityViolationException ex) {
            log.info("Setting '{}' initialized concurrently by another instance.", key);
        }
    }

    /**
     * Seeds demo shop owner STRICTLY in explicit development or test profiles ('dev', 'test', 'local').
     * In production (or when no explicit dev/test profile is declared), this bean is NOT activated,
     * ensuring production database initialization never creates demo credentials or accounts.
     */
    @Bean
    @Profile({"dev", "test", "local"})
    public CommandLineRunner seedDemoData(UserRepository userRepo,
                                          ShopRepository shopRepo,
                                          SubscriptionRepository subscriptionRepo,
                                          TrialEligibilityLedgerRepository trialLedgerRepo,
                                          PasswordEncoder passwordEncoder,
                                          PhoneNormalizationService phoneNormalizationService,
                                          Environment env) {
        return args -> {
            // Defensive secondary guard against accidental multi-profile activation with prod
            String[] activeProfiles = env.getActiveProfiles();
            for (String profile : activeProfiles) {
                if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile) || "staging".equalsIgnoreCase(profile)) {
                    log.info("Production/Staging profile active; demo account seeding permanently bypassed.");
                    return;
                }
            }

            String rawPhone = "9876543210";
            String normalizedPhone = phoneNormalizationService.normalize(rawPhone);
            LocalDateTime now = LocalDateTime.now();
            Instant instantNow = Instant.now();

            var existingOwnerOpt = userRepo.findByEmail("owner@queueless.com");
            if (existingOwnerOpt.isPresent()) {
                User existing = existingOwnerOpt.get();
                if (!normalizedPhone.equals(existing.getPhone())) {
                    existing.setPhone(normalizedPhone);
                    userRepo.save(existing);
                    log.info("Updated existing demo shop owner phone to canonical format: {}", normalizedPhone);
                }
            } else {
                try {
                    User demoUser = new User(
                            "owner@queueless.com",
                            passwordEncoder.encode("123456"),
                            "Ramesh Sharma",
                            normalizedPhone,
                            UserRole.ROLE_OWNER
                    );
                    demoUser.setCredentialsUpdatedAt(instantNow);
                    demoUser.setLastLoginAt(now);
                    demoUser.setFailedPinAttempts(0);
                    demoUser = userRepo.save(demoUser);

                    Shop demoShop = new Shop(
                            demoUser,
                            "Royal Hair Salon & Spa",
                            "Salon & Spa",
                            15
                    );
                    demoShop = shopRepo.save(demoShop);

                    LocalDate today = LocalDate.now();
                    LocalDate expiry = today.plusDays(7);
                    Subscription sub = new Subscription(
                            demoShop,
                            SubscriptionPlan.TRIAL,
                            SubscriptionStatus.ACTIVE,
                            today,
                            expiry,
                            BigDecimal.ZERO
                    );
                    sub.setNotes("Complimentary 7-day initial trial");
                    sub.setTrialStartedAt(now);
                    sub.setTrialEndsAt(now.plusDays(7));
                    sub.setTrialConsumedAt(now);
                    subscriptionRepo.save(sub);

                    TrialEligibilityLedger ledger = new TrialEligibilityLedger();
                    ledger.setNormalizedPhone(normalizedPhone);
                    ledger.setPhoneHmac(phoneNormalizationService.computeHmac(normalizedPhone));
                    ledger.setTrialStartedAt(now);
                    ledger.setTrialEndsAt(now.plusDays(7));
                    ledger.setTrialConsumedAt(now);
                    ledger.setOwnerId(demoUser.getId());
                    ledger.setShopId(demoShop.getId());
                    ledger.setOwnerEmail(demoUser.getEmail());
                    ledger.setRegistrationStatus("ACTIVE");
                    trialLedgerRepo.save(ledger);

                    log.info("Explicit dev/test environment: Demo shop owner seeded (owner@queueless.com)");
                } catch (Exception e) {
                    log.warn("Could not seed demo shop owner (non-fatal): {}", e.getMessage());
                }
            }
        };
    }
}
