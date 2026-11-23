package com.queueless.repository;

import com.queueless.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findTop50ByOrderByCreatedAtDesc();
    List<AuditLog> findTop50ByTargetTypeNotOrderByCreatedAtDesc(String targetType);
    Page<AuditLog> findAllByTargetTypeNotOrderByCreatedAtDesc(String targetType, Pageable pageable);
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<AuditLog> findAllByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, String targetId);
    Optional<AuditLog> findFirstByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, String targetId);
}
