package com.queueless.dto;

import com.queueless.entity.SubscriptionPlan;

public class AdminShopActivateRequest {
    private Long shopId;
    private SubscriptionPlan planType; // MONTHLY or YEARLY
    private Integer customDays; // e.g. 30 or 365

    public AdminShopActivateRequest() {}

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public SubscriptionPlan getPlanType() {
        return planType;
    }

    public void setPlanType(SubscriptionPlan planType) {
        this.planType = planType;
    }

    public Integer getCustomDays() {
        return customDays;
    }

    public void setCustomDays(Integer customDays) {
        this.customDays = customDays;
    }
}
