package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateBusinessRequest {

    @NotBlank(message = "Business name cannot be blank")
    @Size(max = 150, message = "Business name must not exceed 150 characters")
    private String name;

    public CreateBusinessRequest() {}

    public CreateBusinessRequest(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
