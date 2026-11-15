package com.queueless.model;

import com.queueless.entity.AddedBy;
import com.queueless.entity.CustomerQueueStatus;

import java.time.LocalDateTime;

public class QueueCustomerSummary {
    private Long id;
    private String tokenNumber;
    private Integer tokenSequence;
    private String customerName;
    private String customerPhone;
    private String trackingToken;
    private CustomerQueueStatus status;
    private AddedBy addedBy;
    private LocalDateTime joinedAt;
    private LocalDateTime calledAt;
    private LocalDateTime servedAt;
    private LocalDateTime completedAt;
    private Integer lastPushedPosition;

    public QueueCustomerSummary() {}

    public QueueCustomerSummary(Long id, String tokenNumber, Integer tokenSequence, String customerName,
                                String customerPhone, String trackingToken, CustomerQueueStatus status,
                                AddedBy addedBy, LocalDateTime joinedAt, LocalDateTime calledAt,
                                LocalDateTime servedAt, LocalDateTime completedAt) {
        this.id = id;
        this.tokenNumber = tokenNumber;
        this.tokenSequence = tokenSequence;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.trackingToken = trackingToken;
        this.status = status;
        this.addedBy = addedBy;
        this.joinedAt = joinedAt;
        this.calledAt = calledAt;
        this.servedAt = servedAt;
        this.completedAt = completedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTokenNumber() {
        return tokenNumber;
    }

    public void setTokenNumber(String tokenNumber) {
        this.tokenNumber = tokenNumber;
    }

    public Integer getTokenSequence() {
        return tokenSequence;
    }

    public void setTokenSequence(Integer tokenSequence) {
        this.tokenSequence = tokenSequence;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public String getTrackingToken() {
        return trackingToken;
    }

    public void setTrackingToken(String trackingToken) {
        this.trackingToken = trackingToken;
    }

    public CustomerQueueStatus getStatus() {
        return status;
    }

    public void setStatus(CustomerQueueStatus status) {
        this.status = status;
    }

    public AddedBy getAddedBy() {
        return addedBy;
    }

    public void setAddedBy(AddedBy addedBy) {
        this.addedBy = addedBy;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public LocalDateTime getCalledAt() {
        return calledAt;
    }

    public void setCalledAt(LocalDateTime calledAt) {
        this.calledAt = calledAt;
    }

    public LocalDateTime getServedAt() {
        return servedAt;
    }

    public void setServedAt(LocalDateTime servedAt) {
        this.servedAt = servedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getLastPushedPosition() {
        return lastPushedPosition;
    }

    public void setLastPushedPosition(Integer lastPushedPosition) {
        this.lastPushedPosition = lastPushedPosition;
    }
}
