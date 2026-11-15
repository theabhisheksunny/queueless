package com.queueless.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PushNotificationPayload {

    private String title;
    private String body;
    private String icon = "/favicon.svg";
    private String badge = "/favicon.svg";
    private String tag;
    private String url;
    private String trackingToken;
    private String tokenNumber;
    private String status;
    private Integer peopleAhead;
    private String action = "NOTIFY"; // "NOTIFY" or "CLOSE"
    private Long timestamp;

    public PushNotificationPayload() {
        this.timestamp = System.currentTimeMillis();
    }

    public static PushNotificationPayload createNotify(String title, String body, String trackingToken, String tokenNumber, String status, Integer peopleAhead) {
        PushNotificationPayload p = new PushNotificationPayload();
        p.setTitle(title);
        p.setBody(body);
        p.setTrackingToken(trackingToken);
        p.setTokenNumber(tokenNumber);
        p.setStatus(status);
        p.setPeopleAhead(peopleAhead);
        p.setTag("queueless-queue-" + trackingToken);
        p.setUrl("/track/" + trackingToken);
        p.setAction("NOTIFY");
        p.setTimestamp(System.currentTimeMillis());
        return p;
    }

    public static PushNotificationPayload createClose(String trackingToken) {
        PushNotificationPayload p = new PushNotificationPayload();
        p.setTrackingToken(trackingToken);
        p.setTag("queueless-queue-" + trackingToken);
        p.setAction("CLOSE");
        p.setTimestamp(System.currentTimeMillis());
        return p;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getBadge() {
        return badge;
    }

    public void setBadge(String badge) {
        this.badge = badge;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTrackingToken() {
        return trackingToken;
    }

    public void setTrackingToken(String trackingToken) {
        this.trackingToken = trackingToken;
    }

    public String getTokenNumber() {
        return tokenNumber;
    }

    public void setTokenNumber(String tokenNumber) {
        this.tokenNumber = tokenNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getPeopleAhead() {
        return peopleAhead;
    }

    public void setPeopleAhead(Integer peopleAhead) {
        this.peopleAhead = peopleAhead;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
