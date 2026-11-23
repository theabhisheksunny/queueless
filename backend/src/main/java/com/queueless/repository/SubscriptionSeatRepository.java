package com.queueless.repository;

import com.queueless.entity.SubscriptionPlan;
import com.queueless.entity.SubscriptionSeat;
import com.queueless.entity.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionSeatRepository extends JpaRepository<SubscriptionSeat, Long> {
    List<SubscriptionSeat> findAllByOwnerId(Long ownerId);
    List<SubscriptionSeat> findAllByOwnerIdAndStatus(Long ownerId, SubscriptionStatus status);
    Optional<SubscriptionSeat> findByAssignedUserIdAndStatus(Long assignedUserId, SubscriptionStatus status);
    Optional<SubscriptionSeat> findFirstByOwnerIdAndStatusAndAssignedUserIsNullOrderByEndDateDesc(Long ownerId, SubscriptionStatus status);
    long countByOwnerIdAndStatus(Long ownerId, SubscriptionStatus status);
    long countByOwnerIdAndStatusAndAssignedUserIsNotNull(Long ownerId, SubscriptionStatus status);
    boolean existsByPaymentReference(String paymentReference);
}
