package com.queueless.event;

public class QueueEvent {

    public enum Type {
        CUSTOMER_JOINED,
        TOKEN_CALLED,
        SERVICE_STARTED,
        SERVICE_COMPLETED,
        CUSTOMER_LEFT,
        CUSTOMER_REMOVED,
        POSITION_MILESTONE,
        QUEUE_CLOSED
    }

    private final Type type;
    private final Long shopId;
    private final String shopName;
    private final String trackingToken;
    private final String tokenNumber;
    private final Integer peopleAhead;
    private final Integer estimatedWaitMinutes;

    public QueueEvent(Type type, Long shopId, String shopName, String trackingToken, String tokenNumber, Integer peopleAhead, Integer estimatedWaitMinutes) {
        this.type = type;
        this.shopId = shopId;
        this.shopName = shopName;
        this.trackingToken = trackingToken;
        this.tokenNumber = tokenNumber;
        this.peopleAhead = peopleAhead;
        this.estimatedWaitMinutes = estimatedWaitMinutes;
    }

    public static QueueEvent forCustomer(Type type, Long shopId, String shopName, String trackingToken, String tokenNumber, Integer peopleAhead, Integer estimatedWaitMinutes) {
        return new QueueEvent(type, shopId, shopName, trackingToken, tokenNumber, peopleAhead, estimatedWaitMinutes);
    }

    public static QueueEvent forQueueClosed(Long shopId, String shopName) {
        return new QueueEvent(Type.QUEUE_CLOSED, shopId, shopName, null, null, null, null);
    }

    public Type getType() {
        return type;
    }

    public Long getShopId() {
        return shopId;
    }

    public String getShopName() {
        return shopName;
    }

    public String getTrackingToken() {
        return trackingToken;
    }

    public String getTokenNumber() {
        return tokenNumber;
    }

    public Integer getPeopleAhead() {
        return peopleAhead;
    }

    public Integer getEstimatedWaitMinutes() {
        return estimatedWaitMinutes;
    }
}
