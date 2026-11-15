package com.queueless.service;

import com.queueless.entity.Subscription;
import com.queueless.entity.SubscriptionPlan;
import com.queueless.entity.SubscriptionStatus;
import com.queueless.exception.SubscriptionRequiredException;
import com.queueless.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class EntitlementService {

    private final SubscriptionRepository subscriptionRepository;
    private Clock clock;

    public EntitlementService(SubscriptionRepository subscriptionRepository, Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
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
     * - An active, unexpired 7-day trial, OR
     * - An active, unexpired paid subscription (Monthly/Yearly).
     */
    @Transactional(readOnly = true)
    public boolean hasActiveEntitlement(Long shopId) {
        if (shopId == null) {
            return false;
        }

        Subscription sub = subscriptionRepository.findTopByShopIdOrderByEndDateDesc(shopId).orElse(null);
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

    /**
     * Enforces active entitlement on protected owner operations.
     * Throws HTTP 402 Payment Required if expired.
     */
    @Transactional(readOnly = true)
    public void requireActiveEntitlement(Long shopId) {
        if (!hasActiveEntitlement(shopId)) {
            throw new SubscriptionRequiredException(
                    "Your 7-day free trial or subscription has expired. Active subscription is required to perform queue operations."
            );
        }
    }
}
