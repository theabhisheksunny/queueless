package com.queueless.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class AdminUserShopItem {

    private Long ownerId;
    private String ownerName;
    private String phone;
    private String email;
    private LocalDateTime registeredAt;

    private String subscriptionPlan;
    private String subscriptionStatus;
    private LocalDate subscriptionEndDate;

    private List<AdminBusinessSummary> businesses = new ArrayList<>();
    private List<AdminBranchSummary> branches = new ArrayList<>();

    public AdminUserShopItem() {}

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(LocalDateTime registeredAt) {
        this.registeredAt = registeredAt;
    }

    public String getSubscriptionPlan() {
        return subscriptionPlan;
    }

    public void setSubscriptionPlan(String subscriptionPlan) {
        this.subscriptionPlan = subscriptionPlan;
    }

    public String getSubscriptionStatus() {
        return subscriptionStatus;
    }

    public void setSubscriptionStatus(String subscriptionStatus) {
        this.subscriptionStatus = subscriptionStatus;
    }

    public LocalDate getSubscriptionEndDate() {
        return subscriptionEndDate;
    }

    public void setSubscriptionEndDate(LocalDate subscriptionEndDate) {
        this.subscriptionEndDate = subscriptionEndDate;
    }

    public List<AdminBusinessSummary> getBusinesses() {
        return businesses;
    }

    public void setBusinesses(List<AdminBusinessSummary> businesses) {
        this.businesses = businesses;
    }

    public List<AdminBranchSummary> getBranches() {
        return branches;
    }

    public void setBranches(List<AdminBranchSummary> branches) {
        this.branches = branches;
    }

    public static class AdminBusinessSummary {
        private Long id;
        private String name;

        public AdminBusinessSummary() {}

        public AdminBusinessSummary(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class AdminBranchSummary {
        private Long id;
        private String name;
        private String branchName;
        private String businessName;
        private String category;
        private String publicQueueId;
        private boolean isPrimary;
        private String registrationStatus;
        private String address;

        public AdminBranchSummary() {}

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBranchName() {
            return branchName;
        }

        public void setBranchName(String branchName) {
            this.branchName = branchName;
        }

        public String getBusinessName() {
            return businessName;
        }

        public void setBusinessName(String businessName) {
            this.businessName = businessName;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getPublicQueueId() {
            return publicQueueId;
        }

        public void setPublicQueueId(String publicQueueId) {
            this.publicQueueId = publicQueueId;
        }

        public boolean isPrimary() {
            return isPrimary;
        }

        public void setPrimary(boolean primary) {
            isPrimary = primary;
        }

        public String getRegistrationStatus() {
            return registrationStatus;
        }

        public void setRegistrationStatus(String registrationStatus) {
            this.registrationStatus = registrationStatus;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }
    }
}
