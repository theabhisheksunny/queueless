package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class BranchRegistrationPaymentRequest {

    @NotBlank(message = "Payment reference / UTR is required")
    @Size(min = 6, max = 100, message = "Payment reference must be between 6 and 100 characters")
    private String paymentReference;

    @Size(max = 100, message = "UPI ID must not exceed 100 characters")
    private String paymentUpiId;

    public BranchRegistrationPaymentRequest() {}

    public BranchRegistrationPaymentRequest(String paymentReference, String paymentUpiId) {
        this.paymentReference = paymentReference;
        this.paymentUpiId = paymentUpiId;
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
