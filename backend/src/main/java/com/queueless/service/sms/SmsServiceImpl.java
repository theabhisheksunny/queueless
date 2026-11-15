package com.queueless.service.sms;

import com.queueless.exception.SmsProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SmsServiceImpl implements SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsServiceImpl.class);

    private final HttpClient httpClient;
    private final Map<String, String> testSentOtps = new ConcurrentHashMap<>();

    @Value("${app.sms.provider:none}")
    private String provider;

    @Value("${app.sms.api-key:}")
    private String apiKey;

    @Value("${app.sms.sender-id:QUELES}")
    private String senderId;

    @Value("${app.sms.twilio.account-sid:}")
    private String twilioAccountSid;

    @Value("${app.sms.twilio.auth-token:}")
    private String twilioAuthToken;

    @Value("${app.sms.twilio.from-phone:}")
    private String twilioFromPhone;

    @Value("${app.sms.fast2sms.api-key:}")
    private String fast2smsApiKey;

    @Value("${app.sms.webhook-url:}")
    private String webhookUrl;

    public SmsServiceImpl() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    @Override
    public boolean isConfigured() {
        if (provider == null || provider.isBlank() || "none".equalsIgnoreCase(provider)) {
            return false;
        }
        if ("mock".equalsIgnoreCase(provider) || "test".equalsIgnoreCase(provider)) {
            return true;
        }
        if ("twilio".equalsIgnoreCase(provider)) {
            return !twilioAccountSid.isBlank() && !twilioAuthToken.isBlank() && !twilioFromPhone.isBlank();
        }
        if ("fast2sms".equalsIgnoreCase(provider)) {
            return !fast2smsApiKey.isBlank() || !apiKey.isBlank();
        }
        if ("webhook".equalsIgnoreCase(provider) || "generic".equalsIgnoreCase(provider)) {
            return !webhookUrl.isBlank();
        }
        return false;
    }

    @Override
    public void sendOtp(String normalizedPhone, String otp) {
        if (!isConfigured()) {
            throw new SmsProviderUnavailableException(
                    "SMS verification service is currently unavailable. No SMS gateway provider is configured. Please configure an SMS provider (e.g., Fast2SMS or Twilio) in application settings."
            );
        }

        String message = "Your QueueLess verification code is: " + otp + ". Valid for 5 minutes. Do not share this OTP with anyone.";

        String providerName = provider.trim().toLowerCase();
        switch (providerName) {
            case "mock", "test" -> {
                // Store in test map for test verification assertions; NEVER log plain OTP
                testSentOtps.put(normalizedPhone, otp);
                log.info("Test SMS provider captured OTP for {}", normalizedPhone);
            }
            case "twilio" -> sendViaTwilio(normalizedPhone, message);
            case "fast2sms" -> sendViaFast2Sms(normalizedPhone, otp);
            case "webhook", "generic" -> sendViaWebhook(normalizedPhone, otp, message);
            default -> throw new SmsProviderUnavailableException(
                    "Unsupported SMS provider configured: " + provider + ". Supported providers are: none, twilio, fast2sms, webhook, mock."
            );
        }
    }

    @Override
    public String getLastSentOtp(String normalizedPhone) {
        if ("mock".equalsIgnoreCase(provider) || "test".equalsIgnoreCase(provider)) {
            return testSentOtps.get(normalizedPhone);
        }
        return null;
    }

    public void clearTestSentOtps() {
        testSentOtps.clear();
    }

    private void sendViaTwilio(String normalizedPhone, String message) {
        try {
            String twilioUrl = "https://api.twilio.com/2010-04-01/Accounts/" + twilioAccountSid.trim() + "/Messages.json";
            String formBody = "To=" + URLEncoder.encode(normalizedPhone, StandardCharsets.UTF_8)
                    + "&From=" + URLEncoder.encode(twilioFromPhone.trim(), StandardCharsets.UTF_8)
                    + "&Body=" + URLEncoder.encode(message, StandardCharsets.UTF_8);

            String auth = Base64.getEncoder().encodeToString((twilioAccountSid.trim() + ":" + twilioAuthToken.trim()).getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(twilioUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Twilio SMS dispatch failed with HTTP {}: {}", response.statusCode(), response.body());
                throw new IllegalStateException("Failed to deliver SMS via Twilio. Status: " + response.statusCode());
            }
            log.info("SMS OTP dispatched successfully via Twilio to {}", normalizedPhone);
        } catch (Exception e) {
            log.error("Error dispatching SMS via Twilio to {}: {}", normalizedPhone, e.getMessage());
            throw new IllegalStateException("SMS delivery failed: " + e.getMessage(), e);
        }
    }

    private void sendViaFast2Sms(String normalizedPhone, String otp) {
        try {
            String effectiveKey = !fast2smsApiKey.isBlank() ? fast2smsApiKey.trim() : apiKey.trim();
            String rawPhoneDigits = normalizedPhone.replaceAll("[^0-9]", "");
            if (rawPhoneDigits.startsWith("91") && rawPhoneDigits.length() == 12) {
                rawPhoneDigits = rawPhoneDigits.substring(2);
            }

            String jsonPayload = String.format(
                    "{\"route\":\"otp\",\"variables_values\":\"%s\",\"numbers\":\"%s\"}",
                    otp,
                    rawPhoneDigits
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.fast2sms.com/dev/bulkV2"))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", effectiveKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Fast2SMS dispatch failed with HTTP {}: {}", response.statusCode(), response.body());
                throw new IllegalStateException("Failed to deliver SMS via Fast2SMS. Status: " + response.statusCode());
            }
            log.info("SMS OTP dispatched successfully via Fast2SMS to {}", normalizedPhone);
        } catch (Exception e) {
            log.error("Error dispatching SMS via Fast2SMS to {}: {}", normalizedPhone, e.getMessage());
            throw new IllegalStateException("SMS delivery failed: " + e.getMessage(), e);
        }
    }

    private void sendViaWebhook(String normalizedPhone, String otp, String message) {
        try {
            String jsonPayload = String.format(
                    "{\"phone\":\"%s\",\"otp\":\"%s\",\"message\":\"%s\"}",
                    normalizedPhone,
                    otp,
                    message.replace("\"", "\\\"")
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl.trim()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Webhook SMS dispatch failed with HTTP {}: {}", response.statusCode(), response.body());
                throw new IllegalStateException("Failed to deliver SMS via Webhook. Status: " + response.statusCode());
            }
            log.info("SMS OTP dispatched successfully via Webhook to {}", normalizedPhone);
        } catch (Exception e) {
            log.error("Error dispatching SMS via Webhook to {}: {}", normalizedPhone, e.getMessage());
            throw new IllegalStateException("SMS delivery failed: " + e.getMessage(), e);
        }
    }
}
