package com.queueless.repository;

import com.queueless.entity.DeviceAuthChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceAuthChallengeRepository extends JpaRepository<DeviceAuthChallenge, Long> {

    Optional<DeviceAuthChallenge> findByChallenge(String challenge);

    List<DeviceAuthChallenge> findAllByUserIdAndStatus(Long userId, String status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE DeviceAuthChallenge c SET c.status = 'CONSUMED', c.consumedAt = :now WHERE c.id = :id AND c.status = 'PENDING'")
    int consumeChallengeAtomic(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE DeviceAuthChallenge c SET c.status = 'EXPIRED' WHERE c.user.id = :userId AND c.status = 'PENDING' AND c.expiresAt <= :now")
    int expirePendingChallenges(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM DeviceAuthChallenge c WHERE c.expiresAt <= :cutoff")
    int deleteExpiredChallenges(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT c.id FROM DeviceAuthChallenge c WHERE c.expiresAt <= :cutoff")
    List<Long> findExpiredIds(@Param("cutoff") LocalDateTime cutoff, org.springframework.data.domain.Pageable pageable);
}
