package com.queueless.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_push_subscriptions", indexes = {
    @Index(name = "idx_push_sub_customer", columnList = "customer_id, active"),
    @Index(name = "idx_push_sub_shop", columnList = "shop_id, active")
})
public class CustomerPushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private QueueCustomer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(nullable = false, length = 1024)
    private String endpoint;

    @Column(nullable = false, length = 255)
    private String p256dhKey;

    @Column(nullable = false, length = 255)
    private String authSecret;

    @Column(length = 512)
    private String userAgent;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime lastPushAt;

    @Column(length = 64)
    private String lastPushStatus;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public CustomerPushSubscription() {}

    public CustomerPushSubscription(QueueCustomer customer, Shop shop, String endpoint, String p256dhKey, String authSecret, String userAgent) {
        this.customer = customer;
        this.shop = shop;
        this.endpoint = endpoint;
        this.p256dhKey = p256dhKey;
        this.authSecret = authSecret;
        this.userAgent = userAgent;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public QueueCustomer getCustomer() {
        return customer;
    }

    public void setCustomer(QueueCustomer customer) {
        this.customer = customer;
    }

    public Shop getShop() {
        return shop;
    }

    public void setShop(Shop shop) {
        this.shop = shop;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getP256dhKey() {
        return p256dhKey;
    }

    public void setP256dhKey(String p256dhKey) {
        this.p256dhKey = p256dhKey;
    }

    public String getAuthSecret() {
        return authSecret;
    }

    public void setAuthSecret(String authSecret) {
        this.authSecret = authSecret;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getLastPushAt() {
        return lastPushAt;
    }

    public void setLastPushAt(LocalDateTime lastPushAt) {
        this.lastPushAt = lastPushAt;
    }

    public String getLastPushStatus() {
        return lastPushStatus;
    }

    public void setLastPushStatus(String lastPushStatus) {
        this.lastPushStatus = lastPushStatus;
    }
}
