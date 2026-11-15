package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;

public class CategoryChangeInitiateRequest {

    @NotBlank(message = "Requested category is required")
    private String requestedCategory;

    public CategoryChangeInitiateRequest() {}

    public CategoryChangeInitiateRequest(String requestedCategory) {
        this.requestedCategory = requestedCategory;
    }

    public String getRequestedCategory() {
        return requestedCategory;
    }

    public void setRequestedCategory(String requestedCategory) {
        this.requestedCategory = requestedCategory;
    }
}
