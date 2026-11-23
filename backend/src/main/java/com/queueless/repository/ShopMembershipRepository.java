package com.queueless.repository;

import com.queueless.entity.ShopMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShopMembershipRepository extends JpaRepository<ShopMembership, Long> {
    List<ShopMembership> findAllByUserIdAndStatus(Long userId, String status);
    List<ShopMembership> findAllByUserId(Long userId);
    List<ShopMembership> findAllByShopIdAndStatus(Long shopId, String status);
    Optional<ShopMembership> findByUserIdAndShopId(Long userId, Long shopId);
    boolean existsByUserIdAndShopIdAndStatus(Long userId, Long shopId, String status);
    void deleteAllByUserId(Long userId);
}
