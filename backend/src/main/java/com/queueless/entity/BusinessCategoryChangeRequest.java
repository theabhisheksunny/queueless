package com.queueless.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "business_category_change_requests", indexes = {
    @Index(name = "idx_cat_req_shop", columnList = "shopId"),
    @Index(name = "idx_cat_req_status", columnList = "status"),
    @Index(name = "idx_cat_req_utr", columnList = "paymentReference")
})
public class BusinessCategoryChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shopId;

    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 80)
    private String oldCategory;

    @Column(nullable = false, length = 80)
    private String requestedCategory;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount = BusinessCategoryConstants.CATEGORY_CHANGE_FEE;

    @Column(length = 100)
    private String paymentReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CategoryChangeStatus status = CategoryChangeStatus.PAYMENT_PENDING;

    @Column(length = 255)
    private String rejectionReason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime reviewedAt;

    @Column(length = 100)
    private String reviewedBy;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.amount == null) {
            this.amount = BusinessCategoryConstants.CATEGORY_CHANGE_FEE;
        }
        if (this.status == null) {
            this.status = CategoryChangeStatus.PAYMENT_PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public BusinessCategoryChangeRequest() {}

    public BusinessCategoryChangeRequest(Long shopId, Long ownerId, String oldCategory, String requestedCategory) {
        this.shopId = shopId;
        this.ownerId = ownerId;
        this.oldCategory = oldCategory;
        this.requestedCategory = requestedCategory;
        this.amount = BusinessCategoryConstants.CATEGORY_CHANGE_FEE;
        this.status = CategoryChangeStatus.PAYMENT_PENDING;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getShopId() {
        return shopId;
    }

    public void setShopId(Long shopId) {
        this.shopId = shopId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getOldCategory() {
        return oldCategory;
    }

    public void setOldCategory(String oldCategory) {
        this.oldCategory = oldCategory;
    }

    public String getRequestedCategory() {
        return requestedCategory;
    }

    public void setRequestedCategory(String requestedCategory) {
        this.requestedCategory = requestedCategory;
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

    public CategoryChangeStatus getStatus() {
        return status;
    }

    public void setStatus(CategoryChangeStatus status) {
        this.status = status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
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

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }
}
