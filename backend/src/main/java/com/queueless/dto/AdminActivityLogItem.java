package com.queueless.dto;

import java.time.LocalDateTime;

public class AdminActivityLogItem {

    private Long id;
    private String action;
    private String actor;
    private String targetType;
    private String targetId;
    private String details;
    private LocalDateTime createdAt;

    public AdminActivityLogItem() {}

    public AdminActivityLogItem(Long id, String action, String actor, String targetType,
                                String targetId, String details, LocalDateTime createdAt) {
        this.id = id;
        this.action = action;
        this.actor = actor;
        this.targetType = targetType;
        this.targetId = targetId;
        this.details = details;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
