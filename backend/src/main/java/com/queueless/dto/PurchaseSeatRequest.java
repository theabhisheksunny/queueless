package com.queueless.dto;

import com.queueless.entity.SubscriptionPlan;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class PurchaseSeatRequest {

    @NotNull(message = "Plan type is required (MONTHLY or YEARLY)")
    private SubscriptionPlan planType;

    @NotBlank(message = "Payment reference / UTR is required")
    @Size(min = 6, max = 100, message = "Payment reference must be between 6 and 100 characters")
    private String paymentReference;

    @Size(max = 100, message = "UPI ID must not exceed 100 characters")
    private String paymentUpiId;

    public PurchaseSeatRequest() {}

    public PurchaseSeatRequest(SubscriptionPlan planType, String paymentReference, String paymentUpiId) {
        this.planType = planType;
        this.paymentReference = paymentReference;
        this.paymentUpiId = paymentUpiId;
    }

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
