package com.queueless.dto;

import com.queueless.entity.SubscriptionPlan;
import com.queueless.entity.SubscriptionStatus;

import java.time.LocalDate;

public class AdminShopItem {
    private Long shopId;
    private String shopName;
    private String category;
    private String ownerName;
    private String ownerEmail;
    private String ownerPhone;
    private SubscriptionStatus subscriptionStatus;
    private SubscriptionPlan planType;
    private LocalDate subscriptionEndDate;
    private boolean active;
    private String pendingPaymentReference;

    public AdminShopItem() {}

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    public String getOwnerPhone() {
        return ownerPhone;
    }

    public void setOwnerPhone(String ownerPhone) {
        this.ownerPhone = ownerPhone;
    }

    public SubscriptionStatus getSubscriptionStatus() {
        return subscriptionStatus;
    }

    public void setSubscriptionStatus(SubscriptionStatus subscriptionStatus) {
        this.subscriptionStatus = subscriptionStatus;
    }

    public SubscriptionPlan getPlanType() {
        return planType;
    }

    public void setPlanType(SubscriptionPlan planType) {
        this.planType = planType;
    }

    public LocalDate getSubscriptionEndDate() {
        return subscriptionEndDate;
    }

    public void setSubscriptionEndDate(LocalDate subscriptionEndDate) {
        this.subscriptionEndDate = subscriptionEndDate;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getPendingPaymentReference() {
        return pendingPaymentReference;
    }

    public void setPendingPaymentReference(String pendingPaymentReference) {
        this.pendingPaymentReference = pendingPaymentReference;
    }
}
