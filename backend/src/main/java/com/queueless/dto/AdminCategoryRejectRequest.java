package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;

public class AdminCategoryRejectRequest {

    @NotBlank(message = "Rejection reason is required")
    private String reason;

    public AdminCategoryRejectRequest() {}

    public AdminCategoryRejectRequest(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
