package com.queueless.dto;

import com.queueless.entity.UserRole;

public class AuthResponse {
    private String token;
    private String email;
    private String phone;
    private String fullName;
    private UserRole role;
    private Long shopId;
    private String shopName;
    private String category;
    private boolean subscriptionActive;
    private long subscriptionDaysRemaining;
    private java.util.List<BusinessResponse> businesses = new java.util.ArrayList<>();
    private java.util.List<BranchSummaryResponse> branches = new java.util.ArrayList<>();

    public AuthResponse() {}

    public AuthResponse(String token, String email, String fullName, UserRole role,
                        Long shopId, String shopName, String category,
                        boolean subscriptionActive, long subscriptionDaysRemaining) {
        this.token = token;
        this.email = email;
        this.fullName = fullName;
        this.role = role;
        this.shopId = shopId;
        this.shopName = shopName;
        this.category = category;
        this.subscriptionActive = subscriptionActive;
        this.subscriptionDaysRemaining = subscriptionDaysRemaining;
    }

    public AuthResponse(String token, String email, String phone, String fullName, UserRole role,
                        Long shopId, String shopName, String category,
                        boolean subscriptionActive, long subscriptionDaysRemaining) {
        this.token = token;
        this.email = email;
        this.phone = phone;
        this.fullName = fullName;
        this.role = role;
        this.shopId = shopId;
        this.shopName = shopName;
        this.category = category;
        this.subscriptionActive = subscriptionActive;
        this.subscriptionDaysRemaining = subscriptionDaysRemaining;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isSubscriptionActive() {
        return subscriptionActive;
    }

    public void setSubscriptionActive(boolean subscriptionActive) {
        this.subscriptionActive = subscriptionActive;
    }

    public long getSubscriptionDaysRemaining() {
        return subscriptionDaysRemaining;
    }

    public void setSubscriptionDaysRemaining(long subscriptionDaysRemaining) {
        this.subscriptionDaysRemaining = subscriptionDaysRemaining;
    }

    public java.util.List<BusinessResponse> getBusinesses() {
        return businesses;
    }

    public void setBusinesses(java.util.List<BusinessResponse> businesses) {
        this.businesses = businesses;
    }

    public java.util.List<BranchSummaryResponse> getBranches() {
        return branches;
    }

    public void setBranches(java.util.List<BranchSummaryResponse> branches) {
        this.branches = branches;
    }
}
