package com.queueless.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class AdminDashboardSummaryResponse {

    private long totalShopOwners;
    private long totalRegisteredBranches;
    private long activeSubscriptions;
    private long expiredSubscriptions;
    private long pendingPayments;
    private long totalApprovedPayments;
    private BigDecimal totalApprovedAmount = BigDecimal.ZERO;

    private List<AdminPaymentItem> latestPendingPayments;
    private List<AdminPaymentItem> recentlyApprovedPayments;
    private List<RecentOwnerItem> recentlyRegisteredOwners;

    public AdminDashboardSummaryResponse() {}

    public long getTotalShopOwners() {
        return totalShopOwners;
    }

    public void setTotalShopOwners(long totalShopOwners) {
        this.totalShopOwners = totalShopOwners;
    }

    public long getTotalRegisteredBranches() {
        return totalRegisteredBranches;
    }

    public void setTotalRegisteredBranches(long totalRegisteredBranches) {
        this.totalRegisteredBranches = totalRegisteredBranches;
    }

    public long getActiveSubscriptions() {
        return activeSubscriptions;
    }

    public void setActiveSubscriptions(long activeSubscriptions) {
        this.activeSubscriptions = activeSubscriptions;
    }

    public long getExpiredSubscriptions() {
        return expiredSubscriptions;
    }

    public void setExpiredSubscriptions(long expiredSubscriptions) {
        this.expiredSubscriptions = expiredSubscriptions;
    }

    public long getPendingPayments() {
        return pendingPayments;
    }

    public void setPendingPayments(long pendingPayments) {
        this.pendingPayments = pendingPayments;
    }

    public long getTotalApprovedPayments() {
        return totalApprovedPayments;
    }

    public void setTotalApprovedPayments(long totalApprovedPayments) {
        this.totalApprovedPayments = totalApprovedPayments;
    }

    public BigDecimal getTotalApprovedAmount() {
        return totalApprovedAmount;
    }

    public void setTotalApprovedAmount(BigDecimal totalApprovedAmount) {
        this.totalApprovedAmount = totalApprovedAmount;
    }

    public List<AdminPaymentItem> getLatestPendingPayments() {
        return latestPendingPayments;
    }

    public void setLatestPendingPayments(List<AdminPaymentItem> latestPendingPayments) {
        this.latestPendingPayments = latestPendingPayments;
    }

    public List<AdminPaymentItem> getRecentlyApprovedPayments() {
        return recentlyApprovedPayments;
    }

    public void setRecentlyApprovedPayments(List<AdminPaymentItem> recentlyApprovedPayments) {
        this.recentlyApprovedPayments = recentlyApprovedPayments;
    }

    public List<RecentOwnerItem> getRecentlyRegisteredOwners() {
        return recentlyRegisteredOwners;
    }

    public void setRecentlyRegisteredOwners(List<RecentOwnerItem> recentlyRegisteredOwners) {
        this.recentlyRegisteredOwners = recentlyRegisteredOwners;
    }

    public static class RecentOwnerItem {
        private Long ownerId;
        private String ownerName;
        private String phone;
        private String email;
        private LocalDateTime registeredAt;
        private int branchCount;
        private String subscriptionStatus;

        public RecentOwnerItem() {}

        public RecentOwnerItem(Long ownerId, String ownerName, String phone, String email,
                               LocalDateTime registeredAt, int branchCount, String subscriptionStatus) {
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.phone = phone;
            this.email = email;
            this.registeredAt = registeredAt;
            this.branchCount = branchCount;
            this.subscriptionStatus = subscriptionStatus;
        }

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

        public int getBranchCount() {
            return branchCount;
        }

        public void setBranchCount(int branchCount) {
            this.branchCount = branchCount;
        }

        public String getSubscriptionStatus() {
            return subscriptionStatus;
        }

        public void setSubscriptionStatus(String subscriptionStatus) {
            this.subscriptionStatus = subscriptionStatus;
        }
    }
}
