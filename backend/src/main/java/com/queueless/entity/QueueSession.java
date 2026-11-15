package com.queueless.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "queue_sessions", indexes = {
    @Index(name = "idx_queue_session_public_id", columnList = "publicQueueId", unique = true),
    @Index(name = "idx_queue_session_shop_date", columnList = "shop_id, sessionDate")
})
public class QueueSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(nullable = false)
    private LocalDate sessionDate;

    @Column(nullable = false, unique = true, length = 32)
    private String publicQueueId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QueueSessionStatus status = QueueSessionStatus.OPEN;

    @Column(nullable = false)
    private Integer tokenCounter = 0;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime closedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.startedAt == null) {
            this.startedAt = LocalDateTime.now();
        }
        if (this.sessionDate == null) {
            this.sessionDate = LocalDate.now();
        }
        if (this.tokenCounter == null) {
            this.tokenCounter = 0;
        }
    }

    public QueueSession() {}

    public QueueSession(Shop shop, LocalDate sessionDate, String publicQueueId) {
        this.shop = shop;
        this.sessionDate = sessionDate != null ? sessionDate : LocalDate.now();
        this.publicQueueId = publicQueueId;
        this.status = QueueSessionStatus.OPEN;
        this.startedAt = LocalDateTime.now();
        this.tokenCounter = 0;
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

    public LocalDate getSessionDate() {
        return sessionDate;
    }

    public void setSessionDate(LocalDate sessionDate) {
        this.sessionDate = sessionDate;
    }

    public String getPublicQueueId() {
        return publicQueueId;
    }

    public void setPublicQueueId(String publicQueueId) {
        this.publicQueueId = publicQueueId;
    }

    public QueueSessionStatus getStatus() {
        return status;
    }

    public void setStatus(QueueSessionStatus status) {
        this.status = status;
    }

    public Integer getTokenCounter() {
        return tokenCounter;
    }

    public void setTokenCounter(Integer tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
