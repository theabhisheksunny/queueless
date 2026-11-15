package com.queueless;

import com.queueless.entity.ApplicationSetting;
import com.queueless.entity.UserRole;
import com.queueless.repository.ApplicationSettingRepository;
import com.queueless.repository.UserRepository;
import com.queueless.dto.RegisterRequest;
import com.queueless.service.AuthService;
import com.queueless.service.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

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
                                     AuthService authService) {
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

            // Seed demo shop owner for quick instant testing if not existing
            if (!userRepo.existsByEmail("owner@queueless.com")) {
                try {
                    RegisterRequest demoOwner = new RegisterRequest();
                    demoOwner.setFullName("Ramesh Sharma");
                    demoOwner.setEmail("owner@queueless.com");
                    demoOwner.setPassword("password123");
                    demoOwner.setPhone("9876543210");
                    demoOwner.setShopName("Royal Hair Salon & Spa");
                    demoOwner.setCategory("Salon & Spa");
                    demoOwner.setAvgServiceDurationMinutes(15);
                    demoOwner.setOtp("123456");
                    authService.register(demoOwner);
                    log.info("Demo shop owner created: owner@queueless.com / password123");
                } catch (Exception e) {
                    log.warn("Could not seed demo shop owner (non-fatal): {}", e.getMessage());
                }
            }
        };
    }
}
