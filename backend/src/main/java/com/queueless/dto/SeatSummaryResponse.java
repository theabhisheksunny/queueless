package com.queueless.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class SeatSummaryResponse {
    private int includedSeats = 1;
    private long additionalSeatsPurchased = 0;
    private long totalActiveSeats = 1;
    private long assignedSeats = 0;
    private long availableSeats = 1;
    private BigDecimal monthlyPrice = new BigDecimal("47.00");
    private BigDecimal yearlyPrice = new BigDecimal("494.00");
    private String configuredUpiId = "absunny@ybl";
    private String configuredUpiName = "QueueLess Token System";
    private List<SubscriptionSeatResponse> seats = new ArrayList<>();

    public SeatSummaryResponse() {}

    public int getIncludedSeats() {
        return includedSeats;
    }

    public void setIncludedSeats(int includedSeats) {
        this.includedSeats = includedSeats;
    }

    public long getAdditionalSeatsPurchased() {
        return additionalSeatsPurchased;
    }

    public void setAdditionalSeatsPurchased(long additionalSeatsPurchased) {
        this.additionalSeatsPurchased = additionalSeatsPurchased;
    }

    public long getTotalActiveSeats() {
        return totalActiveSeats;
    }

    public void setTotalActiveSeats(long totalActiveSeats) {
        this.totalActiveSeats = totalActiveSeats;
    }

    public long getAssignedSeats() {
        return assignedSeats;
    }

    public void setAssignedSeats(long assignedSeats) {
        this.assignedSeats = assignedSeats;
    }

    public long getAvailableSeats() {
        return availableSeats;
    }

    public void setAvailableSeats(long availableSeats) {
        this.availableSeats = availableSeats;
    }

    public BigDecimal getMonthlyPrice() {
        return monthlyPrice;
    }

    public void setMonthlyPrice(BigDecimal monthlyPrice) {
        this.monthlyPrice = monthlyPrice;
    }

    public BigDecimal getYearlyPrice() {
        return yearlyPrice;
    }

    public void setYearlyPrice(BigDecimal yearlyPrice) {
        this.yearlyPrice = yearlyPrice;
    }

    public String getConfiguredUpiId() {
        return configuredUpiId;
    }

    public void setConfiguredUpiId(String configuredUpiId) {
        this.configuredUpiId = configuredUpiId;
    }

    public String getConfiguredUpiName() {
        return configuredUpiName;
    }

    public void setConfiguredUpiName(String configuredUpiName) {
        this.configuredUpiName = configuredUpiName;
    }

    public List<SubscriptionSeatResponse> getSeats() {
        return seats;
    }

    public void setSeats(List<SubscriptionSeatResponse> seats) {
        this.seats = seats;
    }
}
