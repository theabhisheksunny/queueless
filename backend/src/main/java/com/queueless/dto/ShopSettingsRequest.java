package com.queueless.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public class ShopSettingsRequest {

    @NotBlank(message = "Shop name cannot be blank")
    private String name;

    private String category;

    @Positive(message = "Average service duration must be at least 1 minute")
    private Integer avgServiceDurationMinutes;

    public ShopSettingsRequest() {}

    public ShopSettingsRequest(String name, Integer avgServiceDurationMinutes) {
        this.name = name;
        this.avgServiceDurationMinutes = avgServiceDurationMinutes;
    }

    public ShopSettingsRequest(String name, String category, Integer avgServiceDurationMinutes) {
        this.name = name;
        this.category = category;
        this.avgServiceDurationMinutes = avgServiceDurationMinutes;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getAvgServiceDurationMinutes() {
        return avgServiceDurationMinutes;
    }

    public void setAvgServiceDurationMinutes(Integer avgServiceDurationMinutes) {
        this.avgServiceDurationMinutes = avgServiceDurationMinutes;
    }
}
