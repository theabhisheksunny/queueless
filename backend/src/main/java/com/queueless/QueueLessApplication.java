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
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@SpringBootApplication
@EnableAsync
public class QueueLessApplication {

    private static final Logger log = LoggerFactory.getLogger(QueueLessApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(QueueLessApplication.class, args);
    }

    @Bean
    public CommandLineRunner initData(ApplicationSettingRepository settingRepo,
                                      UserRepository userRepo,
                                      ShopRepository shopRepo,
                                      SubscriptionRepository subscriptionRepo,
                                      TrialEligibilityLedgerRepository trialLedgerRepo,
                                      PasswordEncoder passwordEncoder,
                                      PhoneNormalizationService phoneNormalizationService) {
        return args -> {
            // Seed initial UPI and Pricing settings if missing
            if (settingRepo.findBySettingKey(SubscriptionService.KEY_UPI_ID).isEmpty()) {
                settingRepo.save(new ApplicationSetting(
                        SubscriptionService.KEY_UPI_ID,
                        SubscriptionService.DEFAULT_UPI_ID,
                        "Subscription UPI ID for direct payment"
                ));
            }
            if (settingRepo.findBySettingKey(SubscriptionService.KEY_UPI_NAME).isEmpty()) {
                settingRepo.save(new ApplicationSetting(
                        SubscriptionService.KEY_UPI_NAME,
                        SubscriptionService.DEFAULT_UPI_NAME,
                        "Subscription UPI Merchant/Payee Name"
                ));
            }
            if (settingRepo.findBySettingKey(SubscriptionService.KEY_MONTHLY_PRICE).isEmpty()) {
                settingRepo.save(new ApplicationSetting(
                        SubscriptionService.KEY_MONTHLY_PRICE,
                        SubscriptionService.DEFAULT_MONTHLY_PRICE.toString(),
                        "Monthly plan price in INR"
                ));
            }
            if (settingRepo.findBySettingKey(SubscriptionService.KEY_YEARLY_PRICE).isEmpty()) {
                settingRepo.save(new ApplicationSetting(
                        SubscriptionService.KEY_YEARLY_PRICE,
                        SubscriptionService.DEFAULT_YEARLY_PRICE.toString(),
                        "Yearly plan price in INR"
                ));
            }

            // Seed demo shop owner directly for testing and evaluation without bypass OTP
            if (!userRepo.existsByEmail("owner@queueless.com")) {
                try {
                    String rawPhone = "9876543210";
                    String normalizedPhone = phoneNormalizationService.normalize(rawPhone);
                    LocalDateTime now = LocalDateTime.now();
                    Instant instantNow = Instant.now();

                    User demoUser = new User(
                            "owner@queueless.com",
                            passwordEncoder.encode("password123"),
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

                    log.info("Demo shop owner seeded directly: owner@queueless.com / password123");
                } catch (Exception e) {
                    log.warn("Could not seed demo shop owner (non-fatal): {}", e.getMessage());
                }
            }
        };
    }
}
