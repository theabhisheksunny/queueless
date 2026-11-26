package com.queueless.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "shops")
public class Shop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id")
    private Business business;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "branch_name", length = 150)
    private String branchName;

    @Column(length = 255)
    private String address;

    @Column(length = 20)
    private String phone;

    @Column(nullable = false, length = 80)
    private String category = "General";

    @Column(name = "business_type", length = 100)
    private String businessType;

    @Column(nullable = false)
    private Integer avgServiceDurationMinutes = 15;

    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary = false;

    @Column(name = "registration_status", nullable = false, length = 30)
    private String registrationStatus = "REGISTERED"; // REGISTERED, PAYMENT_PENDING

    @Column(name = "registration_fee_paid", nullable = false, precision = 10, scale = 2)
    private BigDecimal registrationFeePaid = BigDecimal.ZERO;

    @Column(name = "public_merchant_code", nullable = false, unique = true, length = 32)
    private String publicMerchantCode;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.avgServiceDurationMinutes == null || this.avgServiceDurationMinutes <= 0) {
            this.avgServiceDurationMinutes = 15;
        }
        if (this.publicMerchantCode == null || this.publicMerchantCode.isBlank()) {
            this.publicMerchantCode = com.queueless.util.PublicMerchantCodeGenerator.generate();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Shop() {}

    public Shop(User owner, String name, String category, Integer avgServiceDurationMinutes) {
        this.owner = owner;
        this.name = name;
        this.category = category;
        this.avgServiceDurationMinutes = (avgServiceDurationMinutes != null && avgServiceDurationMinutes > 0) ? avgServiceDurationMinutes : 15;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getBusinessType() {
        return businessType;
    }

    public void setBusinessType(String businessType) {
        this.businessType = businessType;
    }

    public Integer getAvgServiceDurationMinutes() {
        return avgServiceDurationMinutes;
    }

    public void setAvgServiceDurationMinutes(Integer avgServiceDurationMinutes) {
        this.avgServiceDurationMinutes = avgServiceDurationMinutes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Business getBusiness() {
        return business;
    }

    public void setBusiness(Business business) {
        this.business = business;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Boolean getIsPrimary() {
        return isPrimary;
    }

    public boolean isPrimary() {
        return Boolean.TRUE.equals(this.isPrimary);
    }

    public void setIsPrimary(Boolean isPrimary) {
        this.isPrimary = isPrimary;
    }

    public String getRegistrationStatus() {
        return registrationStatus;
    }

    public void setRegistrationStatus(String registrationStatus) {
        this.registrationStatus = registrationStatus;
    }

    public boolean isRegistered() {
        return "REGISTERED".equalsIgnoreCase(this.registrationStatus);
    }

    public BigDecimal getRegistrationFeePaid() {
        return registrationFeePaid;
    }

    public void setRegistrationFeePaid(BigDecimal registrationFeePaid) {
        this.registrationFeePaid = registrationFeePaid;
    }

    public String getPublicMerchantCode() {
        return publicMerchantCode;
    }

    public void setPublicMerchantCode(String publicMerchantCode) {
        this.publicMerchantCode = publicMerchantCode;
    }
}
