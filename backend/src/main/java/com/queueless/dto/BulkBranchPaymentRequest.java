package com.queueless.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public class BulkBranchPaymentRequest {

    @NotNull(message = "Branch IDs list cannot be null.")
    @NotEmpty(message = "At least one branch must be selected for payment.")
    private List<Long> branchIds;

    @NotNull(message = "Payment reference (UTR) is required.")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{6,35}$", message = "Invalid UTR format. Must be 6 to 35 alphanumeric characters.")
    private String paymentReference;

    private String paymentUpiId;

    public BulkBranchPaymentRequest() {}

    public BulkBranchPaymentRequest(List<Long> branchIds, String paymentReference, String paymentUpiId) {
        this.branchIds = branchIds;
        this.paymentReference = paymentReference;
        this.paymentUpiId = paymentUpiId;
    }

    public List<Long> getBranchIds() {
        return branchIds;
    }

    public void setBranchIds(List<Long> branchIds) {
        this.branchIds = branchIds;
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
