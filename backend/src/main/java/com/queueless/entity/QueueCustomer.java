package com.queueless.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "queue_customers", indexes = {
    @Index(name = "idx_customer_tracking_token", columnList = "trackingToken", unique = true),
    @Index(name = "idx_customer_session_status", columnList = "queue_session_id, status"),
    @Index(name = "idx_customer_session_seq", columnList = "queue_session_id, tokenSequence")
})
public class QueueCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "queue_session_id", nullable = false)
    private QueueSession queueSession;

    @Column(nullable = false, length = 10)
    private String tokenNumber;

    @Column(nullable = false)
    private Integer tokenSequence;

    @Column(nullable = false, length = 120)
    private String customerName;

    @Column(length = 20)
    private String customerPhone;

    @Column(nullable = false, unique = true, length = 64)
    private String trackingToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerQueueStatus status = CustomerQueueStatus.WAITING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AddedBy addedBy = AddedBy.QR_CODE;

    @Column(nullable = false)
    private LocalDateTime joinedAt;

    private LocalDateTime calledAt;

    private LocalDateTime servedAt;

    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (this.joinedAt == null) {
            this.joinedAt = LocalDateTime.now();
        }
    }

    public QueueCustomer() {}

    public QueueCustomer(QueueSession queueSession, String tokenNumber, Integer tokenSequence,
                         String customerName, String customerPhone, String trackingToken, AddedBy addedBy) {
        this.queueSession = queueSession;
        this.tokenNumber = tokenNumber;
        this.tokenSequence = tokenSequence;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.trackingToken = trackingToken;
        this.addedBy = addedBy != null ? addedBy : AddedBy.QR_CODE;
        this.status = CustomerQueueStatus.WAITING;
        this.joinedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public QueueSession getQueueSession() {
        return queueSession;
    }

    public void setQueueSession(QueueSession queueSession) {
        this.queueSession = queueSession;
    }

    public String getTokenNumber() {
        return tokenNumber;
    }

    public void setTokenNumber(String tokenNumber) {
        this.tokenNumber = tokenNumber;
    }

    public Integer getTokenSequence() {
        return tokenSequence;
    }

    public void setTokenSequence(Integer tokenSequence) {
        this.tokenSequence = tokenSequence;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public String getTrackingToken() {
        return trackingToken;
    }

    public void setTrackingToken(String trackingToken) {
        this.trackingToken = trackingToken;
    }

    public CustomerQueueStatus getStatus() {
        return status;
    }

    public void setStatus(CustomerQueueStatus status) {
        this.status = status;
    }

    public AddedBy getAddedBy() {
        return addedBy;
    }

    public void setAddedBy(AddedBy addedBy) {
        this.addedBy = addedBy;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public LocalDateTime getCalledAt() {
        return calledAt;
    }

    public void setCalledAt(LocalDateTime calledAt) {
        this.calledAt = calledAt;
    }

    public LocalDateTime getServedAt() {
        return servedAt;
    }

    public void setServedAt(LocalDateTime servedAt) {
        this.servedAt = servedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
