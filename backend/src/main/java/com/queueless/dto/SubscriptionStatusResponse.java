package com.queueless.dto;

import com.queueless.entity.SubscriptionPlan;
import com.queueless.entity.SubscriptionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class SubscriptionStatusResponse {
    private SubscriptionStatus status;
    private SubscriptionPlan planType;
    private LocalDate startDate;
    private LocalDate endDate;
    private long daysRemaining;
    private boolean active;
    private String configuredUpiId;
    private String configuredUpiName;
    private BigDecimal monthlyPrice;
    private BigDecimal yearlyPrice;
    private String pendingPaymentReference;
    private LocalDateTime trialStartedAt;
    private LocalDateTime trialEndsAt;
    private LocalDateTime trialConsumedAt;
    private boolean trialExpired;

    public SubscriptionStatusResponse() {}

    public SubscriptionStatus getStatus() {
        return status;
    }

    public void setStatus(SubscriptionStatus status) {
        this.status = status;
    }

    public SubscriptionPlan getPlanType() {
        return planType;
    }

    public void setPlanType(SubscriptionPlan planType) {
        this.planType = planType;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public long getDaysRemaining() {
        return daysRemaining;
    }

    public void setDaysRemaining(long daysRemaining) {
        this.daysRemaining = daysRemaining;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getConfiguredUpiId() {
        return configuredUpiId;
    }

    public void setConfiguredUpiId(String configuredUpiId) {
        this.configuredUpiId = configuredUpiId;
    }

    public String getConfiguredUpiName() {
        return configuredUpiName;
    }

    public void setConfiguredUpiName(String configuredUpiName) {
        this.configuredUpiName = configuredUpiName;
    }

    public BigDecimal getMonthlyPrice() {
        return monthlyPrice;
    }

    public void setMonthlyPrice(BigDecimal monthlyPrice) {
        this.monthlyPrice = monthlyPrice;
    }

    public BigDecimal getYearlyPrice() {
        return yearlyPrice;
    }

    public void setYearlyPrice(BigDecimal yearlyPrice) {
        this.yearlyPrice = yearlyPrice;
    }

    public String getPendingPaymentReference() {
        return pendingPaymentReference;
    }

    public void setPendingPaymentReference(String pendingPaymentReference) {
        this.pendingPaymentReference = pendingPaymentReference;
    }

    public LocalDateTime getTrialStartedAt() {
        return trialStartedAt;
    }

    public void setTrialStartedAt(LocalDateTime trialStartedAt) {
        this.trialStartedAt = trialStartedAt;
    }

    public LocalDateTime getTrialEndsAt() {
        return trialEndsAt;
    }

    public void setTrialEndsAt(LocalDateTime trialEndsAt) {
        this.trialEndsAt = trialEndsAt;
    }

    public LocalDateTime getTrialConsumedAt() {
        return trialConsumedAt;
    }

    public void setTrialConsumedAt(LocalDateTime trialConsumedAt) {
        this.trialConsumedAt = trialConsumedAt;
    }

    public boolean isTrialExpired() {
        return trialExpired;
    }

    public void setTrialExpired(boolean trialExpired) {
        this.trialExpired = trialExpired;
    }
}
