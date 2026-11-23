package com.queueless.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_records", indexes = {
    @Index(name = "idx_payment_rec_ref", columnList = "payment_reference"),
    @Index(name = "idx_payment_rec_owner", columnList = "owner_id, status")
})
public class PaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_type", nullable = false, length = 40)
    private String paymentType; // SUBSCRIPTION, BRANCH_REGISTRATION, USER_SEAT, CATEGORY_CHANGE

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private User owner;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id")
    private Shop shop;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id")
    private SubscriptionSeat seat;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_reference", nullable = false, length = 100)
    private String paymentReference;

    @Column(name = "payment_upi_id", nullable = false, length = 100)
    private String paymentUpiId = "absunny@ybl";

    @Column(nullable = false, length = 30)
    private String status = "PENDING_APPROVAL"; // PENDING_APPROVAL, APPROVED, REJECTED

    @Column(name = "plan_type", length = 20)
    private String planType;

    @Column(name = "batch_shop_ids", length = 500)
    private String batchShopIds;

    @Column(name = "batch_count", nullable = false)
    private Integer batchCount = 1;

    @Column(length = 255)
    private String notes;

    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

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

    public PaymentRecord() {}

    public PaymentRecord(String paymentType, User owner, Shop shop, SubscriptionSeat seat,
                         BigDecimal amount, String paymentReference, String paymentUpiId) {
        this.paymentType = paymentType;
        this.owner = owner;
        this.shop = shop;
        this.seat = seat;
        this.amount = amount;
        this.paymentReference = paymentReference;
        this.paymentUpiId = (paymentUpiId != null && !paymentUpiId.isBlank()) ? paymentUpiId : "absunny@ybl";
        this.status = "PENDING_APPROVAL";
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPaymentType() {
        return paymentType;
    }

    public void setPaymentType(String paymentType) {
        this.paymentType = paymentType;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public Shop getShop() {
        return shop;
    }

    public void setShop(Shop shop) {
        this.shop = shop;
    }

    public SubscriptionSeat getSeat() {
        return seat;
    }

    public void setSeat(SubscriptionSeat seat) {
        this.seat = seat;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPlanType() {
        return planType;
    }

    public void setPlanType(String planType) {
        this.planType = planType;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getBatchShopIds() {
        return batchShopIds;
    }

    public void setBatchShopIds(String batchShopIds) {
        this.batchShopIds = batchShopIds;
    }

    public Integer getBatchCount() {
        return batchCount != null ? batchCount : 1;
    }

    public void setBatchCount(Integer batchCount) {
        this.batchCount = batchCount;
    }
}
