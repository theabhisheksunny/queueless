package com.queueless.dto;

public class SseTicketResponse {
    private String ticket;
    private long expiresInSeconds;

    public SseTicketResponse() {}

    public SseTicketResponse(String ticket, long expiresInSeconds) {
        this.ticket = ticket;
        this.expiresInSeconds = expiresInSeconds;
    }

    public String getTicket() {
        return ticket;
    }

    public void setTicket(String ticket) {
        this.ticket = ticket;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public void setExpiresInSeconds(long expiresInSeconds) {
        this.expiresInSeconds = expiresInSeconds;
    }
}
