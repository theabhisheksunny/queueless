package com.queueless.service;

import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.repository.ApplicationSettingRepository;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class SubscriptionService {

    public static final String KEY_UPI_ID = "SUBSCRIPTION_UPI_ID";
    public static final String KEY_UPI_NAME = "SUBSCRIPTION_UPI_NAME";
    public static final String KEY_MONTHLY_PRICE = "SUBSCRIPTION_MONTHLY_PRICE";
    public static final String KEY_YEARLY_PRICE = "SUBSCRIPTION_YEARLY_PRICE";

    public static final BigDecimal DEFAULT_MONTHLY_PRICE = new BigDecimal("51.00");
    public static final BigDecimal DEFAULT_YEARLY_PRICE = new BigDecimal("501.00");
    public static final String DEFAULT_UPI_ID = "queueless@upi";
    public static final String DEFAULT_UPI_NAME = "QueueLess Token System";

    private final SubscriptionRepository subscriptionRepository;
    private final ShopRepository shopRepository;
    private final ApplicationSettingRepository settingRepository;
    private Clock clock;

    public SubscriptionService(SubscriptionRepository subscriptionRepository,
                               ShopRepository shopRepository,
                               ApplicationSettingRepository settingRepository,
                               Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.shopRepository = shopRepository;
        this.settingRepository = settingRepository;
        this.clock = clock;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public String getConfiguredUpiId() {
        return settingRepository.findBySettingKey(KEY_UPI_ID)
                .map(ApplicationSetting::getSettingValue)
                .orElse(DEFAULT_UPI_ID);
    }

    public String getConfiguredUpiName() {
        return settingRepository.findBySettingKey(KEY_UPI_NAME)
                .map(ApplicationSetting::getSettingValue)
                .orElse(DEFAULT_UPI_NAME);
    }

    public BigDecimal getConfiguredMonthlyPrice() {
        return settingRepository.findBySettingKey(KEY_MONTHLY_PRICE)
                .map(s -> new BigDecimal(s.getSettingValue()))
                .orElse(DEFAULT_MONTHLY_PRICE);
    }

    public BigDecimal getConfiguredYearlyPrice() {
        return settingRepository.findBySettingKey(KEY_YEARLY_PRICE)
                .map(s -> new BigDecimal(s.getSettingValue()))
                .orElse(DEFAULT_YEARLY_PRICE);
    }

    @Transactional(readOnly = true)
    public SubscriptionStatusResponse getShopSubscriptionStatus(Long shopId) {
        Subscription sub = subscriptionRepository.findTopByShopIdOrderByEndDateDesc(shopId).orElse(null);
        Subscription pendingSub = subscriptionRepository.findByShopIdOrderByCreatedAtDesc(shopId).stream()
                .filter(s -> s.getStatus() == SubscriptionStatus.PENDING_APPROVAL)
                .findFirst()
                .orElse(null);

        SubscriptionStatusResponse response = new SubscriptionStatusResponse();
        response.setConfiguredUpiId(getConfiguredUpiId());
        response.setConfiguredUpiName(getConfiguredUpiName());
        response.setMonthlyPrice(getConfiguredMonthlyPrice());
        response.setYearlyPrice(getConfiguredYearlyPrice());

        if (pendingSub != null) {
            response.setPendingPaymentReference(pendingSub.getPaymentReference());
        }

        if (sub != null) {
            response.setStatus(sub.getStatus());
            response.setPlanType(sub.getPlanType());
            response.setStartDate(sub.getStartDate());
            response.setEndDate(sub.getEndDate());
            response.setTrialStartedAt(sub.getTrialStartedAt());
            response.setTrialEndsAt(sub.getTrialEndsAt());
            response.setTrialConsumedAt(sub.getTrialConsumedAt());

            if (response.getPendingPaymentReference() == null) {
                response.setPendingPaymentReference(sub.getPaymentReference());
            }

            LocalDate today = LocalDate.now(clock);
            LocalDateTime nowUtc = LocalDateTime.now(clock);

            boolean isExpired;
            if (sub.getPlanType() == SubscriptionPlan.TRIAL) {
                isExpired = (sub.getTrialEndsAt() != null && !nowUtc.isBefore(sub.getTrialEndsAt())) ||
                            (sub.getEndDate() != null && today.isAfter(sub.getEndDate()));
                response.setTrialExpired(isExpired);
            } else {
                isExpired = sub.getEndDate() != null && today.isAfter(sub.getEndDate());
                response.setTrialExpired(false);
            }

            boolean isActive = sub.getStatus() == SubscriptionStatus.ACTIVE && !isExpired;
            response.setActive(isActive);

            long days = (isActive && sub.getEndDate() != null)
                    ? Math.max(0, ChronoUnit.DAYS.between(today, sub.getEndDate())) : 0;
            response.setDaysRemaining(days);
        } else {
            response.setStatus(SubscriptionStatus.EXPIRED);
            response.setActive(false);
            response.setDaysRemaining(0);
            response.setTrialExpired(true);
        }

        return response;
    }

    @Transactional
    public SubscriptionStatusResponse submitPayment(Long shopId, SubscriptionPaymentSubmitRequest request) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + shopId));

        BigDecimal amount = request.getPlanType() == SubscriptionPlan.YEARLY ?
                getConfiguredYearlyPrice() : getConfiguredMonthlyPrice();

        Subscription sub = new Subscription(
                shop,
                request.getPlanType(),
                SubscriptionStatus.PENDING_APPROVAL,
                LocalDate.now(),
                LocalDate.now(), // will be updated when admin approves
                amount
        );
        sub.setPaymentReference(request.getPaymentReference().trim());
        sub.setPaymentUpiId(request.getPaymentUpiId() != null ? request.getPaymentUpiId().trim() : getConfiguredUpiId());
        sub.setNotes("Payment submitted by shop owner. Pending manual admin verification.");
        subscriptionRepository.save(sub);

        return getShopSubscriptionStatus(shopId);
    }

    @Transactional
    public void adminUpdateSettings(AdminSettingRequest request) {
        if (request.getUpiId() != null && !request.getUpiId().isBlank()) {
            saveOrUpdateSetting(KEY_UPI_ID, request.getUpiId().trim(), "Subscription UPI ID for direct payment");
        }
        if (request.getUpiName() != null && !request.getUpiName().isBlank()) {
            saveOrUpdateSetting(KEY_UPI_NAME, request.getUpiName().trim(), "Subscription UPI Merchant/Payee Name");
        }
        if (request.getMonthlyPrice() != null) {
            saveOrUpdateSetting(KEY_MONTHLY_PRICE, request.getMonthlyPrice().toString(), "Monthly plan price in INR");
        }
        if (request.getYearlyPrice() != null) {
            saveOrUpdateSetting(KEY_YEARLY_PRICE, request.getYearlyPrice().toString(), "Yearly plan price in INR");
        }
    }

    private void saveOrUpdateSetting(String key, String value, String desc) {
        ApplicationSetting setting = settingRepository.findBySettingKey(key)
                .orElse(new ApplicationSetting(key, value, desc));
        setting.setSettingValue(value);
        settingRepository.save(setting);
    }

    @Transactional(readOnly = true)
    public List<AdminShopItem> adminGetAllShops() {
        List<Shop> shops = shopRepository.findAll();
        List<AdminShopItem> items = new ArrayList<>();

        for (Shop s : shops) {
            AdminShopItem item = new AdminShopItem();
            item.setShopId(s.getId());
            item.setShopName(s.getName());
            item.setCategory(s.getCategory());
            if (s.getOwner() != null) {
                item.setOwnerName(s.getOwner().getFullName());
                item.setOwnerEmail(s.getOwner().getEmail());
                item.setOwnerPhone(s.getOwner().getPhone());
            }

            Subscription sub = subscriptionRepository.findTopByShopIdOrderByEndDateDesc(s.getId()).orElse(null);
            if (sub != null) {
                item.setSubscriptionStatus(sub.getStatus());
                item.setPlanType(sub.getPlanType());
                item.setSubscriptionEndDate(sub.getEndDate());
                item.setActive(sub.isCurrentlyActive());
                item.setPendingPaymentReference(sub.getPaymentReference());
            } else {
                item.setSubscriptionStatus(SubscriptionStatus.EXPIRED);
                item.setActive(false);
            }
            items.add(item);
        }
        return items;
    }

    @Transactional
    public void adminActivateShop(AdminShopActivateRequest request) {
        Shop shop = shopRepository.findById(request.getShopId())
                .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + request.getShopId()));

        int daysToAdd = (request.getCustomDays() != null && request.getCustomDays() > 0) ?
                request.getCustomDays() :
                (request.getPlanType() == SubscriptionPlan.YEARLY ? 365 : 30);

        SubscriptionPlan plan = request.getPlanType() != null ? request.getPlanType() :
                (daysToAdd >= 365 ? SubscriptionPlan.YEARLY : SubscriptionPlan.MONTHLY);

        BigDecimal amount = plan == SubscriptionPlan.YEARLY ? getConfiguredYearlyPrice() : getConfiguredMonthlyPrice();

        Subscription latest = subscriptionRepository.findTopByShopIdOrderByEndDateDesc(shop.getId()).orElse(null);
        LocalDate startDate = LocalDate.now(clock);
        LocalDate baseDate = LocalDate.now(clock);

        if (latest != null && latest.getEndDate() != null && !latest.getEndDate().isBefore(LocalDate.now(clock))) {
            // Already active, extend from current end date
            baseDate = latest.getEndDate();
        }

        LocalDate newEndDate = baseDate.plusDays(daysToAdd);

        Subscription activeSub = new Subscription(
                shop,
                plan,
                SubscriptionStatus.ACTIVE,
                startDate,
                newEndDate,
                amount
        );
        activeSub.setNotes("Activated/Extended by administrator for " + daysToAdd + " days");
        subscriptionRepository.save(activeSub);
    }
}
