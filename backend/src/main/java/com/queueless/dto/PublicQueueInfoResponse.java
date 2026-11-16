package com.queueless.dto;

public class PublicQueueInfoResponse {
    private String shopName;
    private String category;
    private boolean queueOpen;
    private int totalWaiting;
    private String currentlyServingToken;

    public PublicQueueInfoResponse() {}

    public PublicQueueInfoResponse(String shopName, String category, boolean queueOpen,
                                   int totalWaiting, String currentlyServingToken) {
        this.shopName = shopName;
        this.category = category;
        this.queueOpen = queueOpen;
        this.totalWaiting = totalWaiting;
        this.currentlyServingToken = currentlyServingToken;
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

    public boolean isQueueOpen() {
        return queueOpen;
    }

    public void setQueueOpen(boolean queueOpen) {
        this.queueOpen = queueOpen;
    }

    public int getTotalWaiting() {
        return totalWaiting;
    }

    public void setTotalWaiting(int totalWaiting) {
        this.totalWaiting = totalWaiting;
    }

    public String getCurrentlyServingToken() {
        return currentlyServingToken;
    }

    public void setCurrentlyServingToken(String currentlyServingToken) {
        this.currentlyServingToken = currentlyServingToken;
    }
}
