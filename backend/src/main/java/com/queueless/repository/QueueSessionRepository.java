package com.queueless.repository;

import com.queueless.entity.QueueSession;
import com.queueless.entity.QueueSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface QueueSessionRepository extends JpaRepository<QueueSession, Long> {
    Optional<QueueSession> findByPublicQueueId(String publicQueueId);
    Optional<QueueSession> findTopByShopIdAndStatusOrderByStartedAtDesc(Long shopId, QueueSessionStatus status);
    Optional<QueueSession> findTopByShopIdAndSessionDateOrderByStartedAtDesc(Long shopId, LocalDate sessionDate);
    List<QueueSession> findByShopIdOrderBySessionDateDesc(Long shopId);
}
