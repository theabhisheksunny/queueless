package com.queueless.repository;

import com.queueless.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByOwnerIdAndScopeAndIdempotencyKey(Long ownerId, String scope, String idempotencyKey);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt <= :now")
    int deleteExpiredKeys(@Param("now") LocalDateTime now);

    @Query("SELECT k.id FROM IdempotencyKey k WHERE k.expiresAt <= :now")
    java.util.List<Long> findExpiredIds(@Param("now") LocalDateTime now, org.springframework.data.domain.Pageable pageable);
}
