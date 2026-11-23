package com.queueless.repository;

import com.queueless.entity.DeviceReplacementRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceReplacementRequestRepository extends JpaRepository<DeviceReplacementRequest, Long> {
    List<DeviceReplacementRequest> findAllByRequestStatusOrderByRequestedAtDesc(String requestStatus);
    List<DeviceReplacementRequest> findAllByUserIdOrderByRequestedAtDesc(Long userId);
    Optional<DeviceReplacementRequest> findTopByUserIdAndRequestStatusOrderByRequestedAtDesc(Long userId, String requestStatus);
    boolean existsByUserIdAndRequestStatus(Long userId, String requestStatus);

    @Query("SELECT r FROM DeviceReplacementRequest r WHERE r.user.owner.id = :ownerId ORDER BY r.requestedAt DESC")
    List<DeviceReplacementRequest> findAllByOwnerId(@Param("ownerId") Long ownerId);

    @Query("SELECT r FROM DeviceReplacementRequest r WHERE r.user.owner.id = :ownerId AND r.requestStatus = :status ORDER BY r.requestedAt DESC")
    List<DeviceReplacementRequest> findAllByOwnerIdAndStatus(@Param("ownerId") Long ownerId, @Param("status") String status);
    Optional<DeviceReplacementRequest> findTopByUserIdAndRequestStatusOrderByReviewedAtDesc(Long userId, String requestStatus);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE DeviceReplacementRequest r SET r.requestStatus = 'APPROVED', r.reviewedBy = :reviewedBy, r.approvalRole = :role, r.reviewedAt = :now WHERE r.id = :id AND r.requestStatus = 'PENDING' AND r.expiresAt > :now")
    int approveAtomic(@Param("id") Long id, @Param("reviewedBy") String reviewedBy, @Param("role") String role, @Param("now") java.time.LocalDateTime now);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE DeviceReplacementRequest r SET r.requestStatus = 'COMPLETED' WHERE r.id = :id AND r.requestStatus = 'APPROVED' AND r.expiresAt > :now")
    int completeAtomic(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);
}
