package com.queueless.repository;

import com.queueless.entity.Subscription;
import com.queueless.entity.SubscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    Optional<Subscription> findTopByShopIdOrderByEndDateDesc(Long shopId);
    List<Subscription> findByShopIdOrderByCreatedAtDesc(Long shopId);
    Optional<Subscription> findTopByOwnerIdOrderByEndDateDesc(Long ownerId);
    List<Subscription> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);
    List<Subscription> findAllByOrderByCreatedAtDesc();
    List<Subscription> findAllByStatusOrderByCreatedAtDesc(SubscriptionStatus status);
    Page<Subscription> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<Subscription> findAllByStatusOrderByCreatedAtDesc(SubscriptionStatus status, Pageable pageable);
    long countByStatus(SubscriptionStatus status);
    boolean existsByPaymentReference(String paymentReference);

    @Query("SELECT s FROM Subscription s WHERE s.owner.id IN :ownerIds ORDER BY s.endDate DESC")
    List<Subscription> findAllByOwnerIdInOrderByEndDateDesc(@Param("ownerIds") Collection<Long> ownerIds);

    @Query("SELECT s FROM Subscription s WHERE s.shop.id IN :shopIds ORDER BY s.endDate DESC")
    List<Subscription> findAllByShopIdInOrderByEndDateDesc(@Param("shopIds") Collection<Long> shopIds);
}
