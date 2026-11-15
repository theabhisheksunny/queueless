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

    Optional<CustomerPushSubscription> findByEndpoint(String endpoint);

    Optional<CustomerPushSubscription> findByCustomerTrackingTokenAndEndpoint(String trackingToken, String endpoint);

    List<CustomerPushSubscription> findByShopIdAndActiveTrue(Long shopId);
}
