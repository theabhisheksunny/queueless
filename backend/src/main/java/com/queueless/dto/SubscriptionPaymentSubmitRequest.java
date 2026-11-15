package com.queueless.dto;

import com.queueless.entity.SubscriptionPlan;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class SubscriptionPaymentSubmitRequest {

    @NotNull(message = "Plan type is required")
    private SubscriptionPlan planType; // MONTHLY (₹51) or YEARLY (₹501)

    @NotBlank(message = "UPI Transaction Reference (UTR) is required")
    private String paymentReference;

    private String paymentUpiId;

    public SubscriptionPaymentSubmitRequest() {}

    public SubscriptionPlan getPlanType() {
        return planType;
    }

    public void setPlanType(SubscriptionPlan planType) {
        this.planType = planType;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public String getPaymentUpiId() {
        return paymentUpiId;
    }

    public void setPaymentUpiId(String paymentUpiId) {
        this.paymentUpiId = paymentUpiId;
    }
}
