package com.queueless.dto;

import com.queueless.entity.CustomerQueueStatus;

public class CustomerJoinResponse {
    private String tokenNumber;
    private String trackingToken;
    private String shopName;
    private int peopleAhead;
    private int estimatedWaitMinutes;
    private CustomerQueueStatus status;

    public CustomerJoinResponse() {}

    public CustomerJoinResponse(String tokenNumber, String trackingToken, String shopName,
                                int peopleAhead, int estimatedWaitMinutes, CustomerQueueStatus status) {
        this.tokenNumber = tokenNumber;
        this.trackingToken = trackingToken;
        this.shopName = shopName;
        this.peopleAhead = peopleAhead;
        this.estimatedWaitMinutes = estimatedWaitMinutes;
        this.status = status;
    }

    public String getTokenNumber() {
        return tokenNumber;
    }

    public void setTokenNumber(String tokenNumber) {
        this.tokenNumber = tokenNumber;
    }

    public String getTrackingToken() {
        return trackingToken;
    }

    public void setTrackingToken(String trackingToken) {
        this.trackingToken = trackingToken;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
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
}
