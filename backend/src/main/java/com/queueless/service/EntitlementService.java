package com.queueless.service;

import com.queueless.entity.Shop;
import com.queueless.entity.Subscription;
import com.queueless.entity.SubscriptionPlan;
import com.queueless.entity.SubscriptionStatus;
import com.queueless.entity.User;
import com.queueless.exception.SubscriptionRequiredException;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class EntitlementService {

    private final SubscriptionRepository subscriptionRepository;
    private final ShopRepository shopRepository;
    private Clock clock;

    public EntitlementService(SubscriptionRepository subscriptionRepository,
                              ShopRepository shopRepository,
                              Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.shopRepository = shopRepository;
        this.clock = clock;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public Clock getClock() {
        return clock;
    }

    /**
     * Checks whether the shop currently holds a valid entitlement:
     * - Physical branch must be registered (if additional branch, requires one-time ₹99 approval)
     * - An active, unexpired 7-day trial, OR
     * - An active, unexpired paid owner subscription (Monthly/Yearly).
     */
    @Transactional(readOnly = true)
    public boolean hasActiveEntitlement(Long shopId) {
        if (shopId == null) {
            return false;
        }

        Shop shop = shopRepository.findById(shopId).orElse(null);
        if (shop != null) {
            // Secondary branch must be registered
            if (!shop.isPrimary() && !shop.isRegistered()) {
                return false;
            }
        }

        // Check owner subscription first, fallback to shop subscription
        Subscription sub = null;
        if (shop != null && shop.getOwner() != null) {
            sub = subscriptionRepository.findTopByOwnerIdOrderByEndDateDesc(shop.getOwner().getId()).orElse(null);
        }
        if (sub == null) {
            sub = subscriptionRepository.findTopByShopIdOrderByEndDateDesc(shopId).orElse(null);
        }

        if (sub == null || sub.getStatus() != SubscriptionStatus.ACTIVE) {
            return false;
        }

        LocalDate today = LocalDate.now(clock);
        LocalDateTime nowUtc = LocalDateTime.now(clock);

        if (sub.getPlanType() == SubscriptionPlan.TRIAL) {
            // Check exact UTC boundary timestamp if present
            if (sub.getTrialEndsAt() != null) {
                if (nowUtc.isAfter(sub.getTrialEndsAt()) || nowUtc.isEqual(sub.getTrialEndsAt())) {
                    return false;
                }
            }
            // Check calendar date boundary
            if (sub.getEndDate() != null && today.isAfter(sub.getEndDate())) {
                return false;
            }
            return true;
        } else {
            // Paid subscription (MONTHLY / YEARLY)
            if (sub.getEndDate() == null || today.isAfter(sub.getEndDate())) {
                return false;
            }
            return true;
        }
    }

    @Transactional(readOnly = true)
    public boolean hasActiveOwnerEntitlement(Long ownerId) {
        if (ownerId == null) {
            return false;
        }
        Subscription sub = subscriptionRepository.findTopByOwnerIdOrderByEndDateDesc(ownerId).orElse(null);
        if (sub == null || sub.getStatus() != SubscriptionStatus.ACTIVE) {
            return false;
        }
        LocalDate today = LocalDate.now(clock);
        LocalDateTime nowUtc = LocalDateTime.now(clock);
        if (sub.getPlanType() == SubscriptionPlan.TRIAL) {
            if (sub.getTrialEndsAt() != null && !nowUtc.isBefore(sub.getTrialEndsAt())) {
                return false;
            }
            return sub.getEndDate() == null || !today.isAfter(sub.getEndDate());
        } else {
            return sub.getEndDate() != null && !today.isAfter(sub.getEndDate());
        }
    }

    /**
     * Enforces active entitlement on protected owner operations.
     * Throws HTTP 402 Payment Required if expired.
     */
    @Transactional(readOnly = true)
    public void requireActiveEntitlement(Long shopId) {
        if (!hasActiveEntitlement(shopId)) {
            Shop shop = shopRepository.findById(shopId).orElse(null);
            if (shop != null && !shop.isPrimary() && !shop.isRegistered()) {
                throw new SubscriptionRequiredException(
                        "This physical branch requires a one-time \u20B999 registration payment before it can perform queue operations."
                );
            }
            throw new SubscriptionRequiredException(
                    "Your 7-day free trial or subscription has expired. Active subscription is required to perform queue operations."
            );
        }
    }
}
