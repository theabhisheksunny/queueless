package com.queueless.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "subscriptions", indexes = {
    @Index(name = "idx_subscription_shop", columnList = "shop_id"),
    @Index(name = "idx_subscription_status", columnList = "status")
})
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionPlan planType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(length = 100)
    private String paymentReference;

    @Column(length = 100)
    private String paymentUpiId;

    @Column(length = 255)
    private String notes;

    @Column(name = "trial_started_at")
    private LocalDateTime trialStartedAt;

    @Column(name = "trial_ends_at")
    private LocalDateTime trialEndsAt;

    @Column(name = "trial_consumed_at")
    private LocalDateTime trialConsumedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Subscription() {}

    public Subscription(Shop shop, SubscriptionPlan planType, SubscriptionStatus status,
                        LocalDate startDate, LocalDate endDate, BigDecimal amount) {
        this.shop = shop;
        if (shop != null && shop.getOwner() != null) {
            this.owner = shop.getOwner();
        }
        this.planType = planType;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.amount = amount != null ? amount : BigDecimal.ZERO;
    }

    public Subscription(User owner, Shop shop, SubscriptionPlan planType, SubscriptionStatus status,
                        LocalDate startDate, LocalDate endDate, BigDecimal amount) {
        this.owner = owner;
        this.shop = shop;
        this.planType = planType;
        this.status = status;
        this.startDate = startDate;
        this.endDate = endDate;
        this.amount = amount != null ? amount : BigDecimal.ZERO;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Shop getShop() {
        return shop;
    }

    public void setShop(Shop shop) {
        this.shop = shop;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public SubscriptionPlan getPlanType() {
        return planType;
    }

    public void setPlanType(SubscriptionPlan planType) {
        this.planType = planType;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public void setStatus(SubscriptionStatus status) {
        this.status = status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public String getPaymentUpiId() {
        return paymentUpiId;
    }

    public void setPaymentUpiId(String paymentUpiId) {
        this.paymentUpiId = paymentUpiId;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getTrialStartedAt() {
        return trialStartedAt;
    }

    public void setTrialStartedAt(LocalDateTime trialStartedAt) {
        this.trialStartedAt = trialStartedAt;
    }

    public LocalDateTime getTrialEndsAt() {
        return trialEndsAt;
    }

    public void setTrialEndsAt(LocalDateTime trialEndsAt) {
        this.trialEndsAt = trialEndsAt;
    }

    public LocalDateTime getTrialConsumedAt() {
        return trialConsumedAt;
    }

    public void setTrialConsumedAt(LocalDateTime trialConsumedAt) {
        this.trialConsumedAt = trialConsumedAt;
    }

    public boolean isCurrentlyActive() {
        return this.status == SubscriptionStatus.ACTIVE &&
               (this.endDate == null || !this.endDate.isBefore(LocalDate.now()));
    }
}
