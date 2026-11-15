package com.queueless.model;

import com.queueless.entity.CustomerQueueStatus;

import java.time.LocalDateTime;

public class CustomerPositionInfo {
    private String shopName;
    private String tokenNumber;
    private String currentlyServingToken;
    private int peopleAhead;
    private int estimatedWaitMinutes;
    private CustomerQueueStatus status;
    private boolean queueOpen;
    private String trackingToken;
    private LocalDateTime joinedAt;

    public CustomerPositionInfo() {}

    public CustomerPositionInfo(String shopName, String tokenNumber, String currentlyServingToken,
                                int peopleAhead, int estimatedWaitMinutes, CustomerQueueStatus status,
                                boolean queueOpen, String trackingToken, LocalDateTime joinedAt) {
        this.shopName = shopName;
        this.tokenNumber = tokenNumber;
        this.currentlyServingToken = currentlyServingToken;
        this.peopleAhead = peopleAhead;
        this.estimatedWaitMinutes = estimatedWaitMinutes;
        this.status = status;
        this.queueOpen = queueOpen;
        this.trackingToken = trackingToken;
        this.joinedAt = joinedAt;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
    }

    public String getTokenNumber() {
        return tokenNumber;
    }

    public void setTokenNumber(String tokenNumber) {
        this.tokenNumber = tokenNumber;
    }

    public String getCurrentlyServingToken() {
        return currentlyServingToken;
    }

    public void setCurrentlyServingToken(String currentlyServingToken) {
        this.currentlyServingToken = currentlyServingToken;
    }

    public int getPeopleAhead() {
        return peopleAhead;
    }

    public void setPeopleAhead(int peopleAhead) {
        this.peopleAhead = peopleAhead;
    }

    public int getEstimatedWaitMinutes() {
        return estimatedWaitMinutes;
    }

    public void setEstimatedWaitMinutes(int estimatedWaitMinutes) {
        this.estimatedWaitMinutes = estimatedWaitMinutes;
    }

    public CustomerQueueStatus getStatus() {
        return status;
    }

    public void setStatus(CustomerQueueStatus status) {
        this.status = status;
    }

    public boolean isQueueOpen() {
        return queueOpen;
    }

    public void setQueueOpen(boolean queueOpen) {
        this.queueOpen = queueOpen;
    }

    public String getTrackingToken() {
        return trackingToken;
    }

    public void setTrackingToken(String trackingToken) {
        this.trackingToken = trackingToken;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }
}
