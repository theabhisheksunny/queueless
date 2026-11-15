package com.queueless.entity;

import java.math.BigDecimal;
import java.util.List;

public final class BusinessCategoryConstants {

    private BusinessCategoryConstants() {}

    public static final BigDecimal CATEGORY_CHANGE_FEE = new BigDecimal("101.00");

    public static final List<String> VALID_CATEGORIES = List.of(
            "Barber Shop",
            "Salon & Spa",
            "Clinic & Doctor",
            "Small Hospital",
            "Restaurant / Cafe",
            "Repair Shop",
            "Automobile Service",
            "Other Local Business"
    );

    public static boolean isValidCategory(String category) {
        if (category == null || category.isBlank()) {
            return false;
        }
        return VALID_CATEGORIES.stream()
                .anyMatch(c -> c.equalsIgnoreCase(category.trim()));
    }
}
