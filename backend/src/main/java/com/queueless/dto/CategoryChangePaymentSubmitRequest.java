package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CategoryChangePaymentSubmitRequest {

    @NotNull(message = "Request ID is required")
    private Long requestId;

    @NotBlank(message = "Payment reference (UTR) is required")
    private String paymentReference;

    public CategoryChangePaymentSubmitRequest() {}

    public CategoryChangePaymentSubmitRequest(Long requestId, String paymentReference) {
        this.requestId = requestId;
        this.paymentReference = paymentReference;
    }

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }
}
