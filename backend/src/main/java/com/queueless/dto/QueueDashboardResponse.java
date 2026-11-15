package com.queueless.dto;

import com.queueless.model.QueueCustomerSummary;

import java.time.LocalDate;
import java.util.List;

public class QueueDashboardResponse {
    private Long shopId;
    private String shopName;
    private String category;
    private Integer avgServiceDurationMinutes;

    private Long sessionId;
    private String publicQueueId;
    private String publicJoinUrl;
    private boolean queueOpen;

    private int totalCustomers;
    private int waitingCustomers;
    private String currentlyServingToken;
    private String currentlyServingName;

    private List<QueueCustomerSummary> customers;

    private String subscriptionStatus;
    private long subscriptionDaysRemaining;
    private LocalDate subscriptionEndDate;

    public QueueDashboardResponse() {}

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

    public Integer getAvgServiceDurationMinutes() {
        return avgServiceDurationMinutes;
    }

    public void setAvgServiceDurationMinutes(Integer avgServiceDurationMinutes) {
        this.avgServiceDurationMinutes = avgServiceDurationMinutes;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getPublicQueueId() {
        return publicQueueId;
    }

    public void setPublicQueueId(String publicQueueId) {
        this.publicQueueId = publicQueueId;
    }

    public String getPublicJoinUrl() {
        return publicJoinUrl;
    }

    public void setPublicJoinUrl(String publicJoinUrl) {
        this.publicJoinUrl = publicJoinUrl;
    }

    public boolean isQueueOpen() {
        return queueOpen;
    }

    public void setQueueOpen(boolean queueOpen) {
        this.queueOpen = queueOpen;
    }

    public int getTotalCustomers() {
        return totalCustomers;
    }

    public void setTotalCustomers(int totalCustomers) {
        this.totalCustomers = totalCustomers;
    }

    public int getWaitingCustomers() {
        return waitingCustomers;
    }

    public void setWaitingCustomers(int waitingCustomers) {
        this.waitingCustomers = waitingCustomers;
    }

    public String getCurrentlyServingToken() {
        return currentlyServingToken;
    }

    public void setCurrentlyServingToken(String currentlyServingToken) {
        this.currentlyServingToken = currentlyServingToken;
    }

    public String getCurrentlyServingName() {
        return currentlyServingName;
    }

    public void setCurrentlyServingName(String currentlyServingName) {
        this.currentlyServingName = currentlyServingName;
    }

    public List<QueueCustomerSummary> getCustomers() {
        return customers;
    }

    public void setCustomers(List<QueueCustomerSummary> customers) {
        this.customers = customers;
    }

    public String getSubscriptionStatus() {
        return subscriptionStatus;
    }

    public void setSubscriptionStatus(String subscriptionStatus) {
        this.subscriptionStatus = subscriptionStatus;
    }

    public long getSubscriptionDaysRemaining() {
        return subscriptionDaysRemaining;
    }

    public void setSubscriptionDaysRemaining(long subscriptionDaysRemaining) {
        this.subscriptionDaysRemaining = subscriptionDaysRemaining;
    }

    public LocalDate getSubscriptionEndDate() {
        return subscriptionEndDate;
    }

    public void setSubscriptionEndDate(LocalDate subscriptionEndDate) {
        this.subscriptionEndDate = subscriptionEndDate;
    }
}
