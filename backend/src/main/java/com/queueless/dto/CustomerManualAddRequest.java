package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CustomerManualAddRequest {

    @NotBlank(message = "Customer name is required")
    @Size(max = 120, message = "Customer name is too long")
    private String customerName;

    @Size(max = 20, message = "Phone number is too long")
    private String customerPhone;

    public CustomerManualAddRequest() {}

    public CustomerManualAddRequest(String customerName, String customerPhone) {
        this.customerName = customerName;
        this.customerPhone = customerPhone;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }
}
