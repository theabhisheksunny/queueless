package com.queueless.repository;

import com.queueless.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    Optional<Subscription> findTopByShopIdOrderByEndDateDesc(Long shopId);
    List<Subscription> findByShopIdOrderByCreatedAtDesc(Long shopId);
    List<Subscription> findAllByOrderByCreatedAtDesc();
}
