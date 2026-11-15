package com.queueless.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "business_category_change_audits", indexes = {
    @Index(name = "idx_cat_audit_shop", columnList = "shopId"),
    @Index(name = "idx_cat_audit_req", columnList = "requestId")
})
public class BusinessCategoryChangeAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shopId;

    @Column(nullable = false, length = 80)
    private String oldCategory;

    @Column(nullable = false, length = 80)
    private String newCategory;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amountPaid;

    @Column(nullable = false)
    private Long requestId;

    @Column(nullable = false, length = 100)
    private String approvedBy;

    @Column(nullable = false)
    private LocalDateTime approvedAt;

    public BusinessCategoryChangeAudit() {}

    public BusinessCategoryChangeAudit(Long shopId, String oldCategory, String newCategory,
                                       BigDecimal amountPaid, Long requestId,
                                       String approvedBy, LocalDateTime approvedAt) {
        this.shopId = shopId;
        this.oldCategory = oldCategory;
        this.newCategory = newCategory;
        this.amountPaid = amountPaid;
        this.requestId = requestId;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
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

    public String getOldCategory() {
        return oldCategory;
    }

    public void setOldCategory(String oldCategory) {
        this.oldCategory = oldCategory;
    }

    public String getNewCategory() {
        return newCategory;
    }

    public void setNewCategory(String newCategory) {
        this.newCategory = newCategory;
    }

    public BigDecimal getAmountPaid() {
        return amountPaid;
    }

    public void setAmountPaid(BigDecimal amountPaid) {
        this.amountPaid = amountPaid;
    }

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
    }
}
