package com.queueless.entity;

import java.math.BigDecimal;
import java.util.List;

public final class BusinessCategoryConstants {

    private BusinessCategoryConstants() {}

    public static final BigDecimal CATEGORY_CHANGE_FEE = new BigDecimal("101.00");

    public static final List<String> BROAD_CATEGORIES = List.of(
            "Salon & Personal Care",
            "Healthcare & Medical",
            "Restaurant & Food",
            "Retail & Shopping",
            "Repair & Maintenance",
            "Automobile Services",
            "Education & Coaching",
            "Professional Services",
            "Home & Local Services",
            "Other Business"
    );

    public static final List<String> HISTORICAL_CATEGORIES = List.of(
            "Barber Shop",
            "Salon & Spa",
            "Clinic & Doctor",
            "Small Hospital",
            "Restaurant / Cafe",
            "Repair Shop",
            "Automobile Service",
            "Other Local Business",
            "General"
    );

    public static final List<String> VALID_CATEGORIES = BROAD_CATEGORIES;

    public static boolean isValidCategory(String category) {
        if (category == null || category.isBlank()) {
            return false;
        }
        String trimmed = category.trim();
        return BROAD_CATEGORIES.stream().anyMatch(c -> c.equalsIgnoreCase(trimmed))
                || HISTORICAL_CATEGORIES.stream().anyMatch(c -> c.equalsIgnoreCase(trimmed));
    }
}
