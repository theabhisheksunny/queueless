package com.queueless.repository;

import com.queueless.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {
    Optional<UserSession> findBySessionId(String sessionId);
    Optional<UserSession> findByUserIdAndStatus(Long userId, String status);
    List<UserSession> findAllByUserId(Long userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    void deleteAllByUserId(Long userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM UserSession s WHERE s.status = 'REVOKED' AND s.lastActiveAt <= :cutoff")
    int deleteStaleRevokedSessions(@org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);

    @org.springframework.data.jpa.repository.Query("SELECT s.id FROM UserSession s WHERE s.status = 'REVOKED' AND s.lastActiveAt <= :cutoff")
    List<Long> findStaleRevokedSessionIds(@org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff, org.springframework.data.domain.Pageable pageable);
}
