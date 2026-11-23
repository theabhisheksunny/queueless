package com.queueless.repository;

import com.queueless.entity.CustomerPushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerPushSubscriptionRepository extends JpaRepository<CustomerPushSubscription, Long> {

    List<CustomerPushSubscription> findByCustomerIdAndActiveTrue(Long customerId);

    List<CustomerPushSubscription> findByCustomerTrackingTokenAndActiveTrue(String trackingToken);

    List<CustomerPushSubscription> findByCustomerTrackingToken(String trackingToken);

    List<CustomerPushSubscription> findByEndpoint(String endpoint);

    List<CustomerPushSubscription> findByCustomerTrackingTokenAndEndpoint(String trackingToken, String endpoint);

    List<CustomerPushSubscription> findByShopIdAndActiveTrue(Long shopId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM CustomerPushSubscription p WHERE p.active = false OR p.createdAt <= :cutoff")
    int deleteStaleSubscriptions(@org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);

    @org.springframework.data.jpa.repository.Query("SELECT p.id FROM CustomerPushSubscription p WHERE p.active = false OR p.createdAt <= :cutoff")
    List<Long> findStaleSubscriptionIds(@org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff, org.springframework.data.domain.Pageable pageable);
}
