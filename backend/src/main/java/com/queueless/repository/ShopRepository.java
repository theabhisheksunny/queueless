package com.queueless.repository;

import com.queueless.entity.Shop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShopRepository extends JpaRepository<Shop, Long> {
    Optional<Shop> findByOwnerId(Long ownerId);
    List<Shop> findAllByOwnerIdOrderByCreatedAtAsc(Long ownerId);
    Optional<Shop> findFirstByOwnerIdOrderByIsPrimaryDescCreatedAtAsc(Long ownerId);
    List<Shop> findAllByBusinessIdOrderByCreatedAtAsc(Long businessId);
    Optional<Shop> findByIdAndOwnerId(Long id, Long ownerId);
    Optional<Shop> findByPublicMerchantCode(String publicMerchantCode);
    boolean existsByPublicMerchantCode(String publicMerchantCode);
    long countByRegistrationStatus(String registrationStatus);

    @Query("SELECT s FROM Shop s LEFT JOIN FETCH s.business WHERE s.owner.id IN :ownerIds ORDER BY s.createdAt ASC")
    List<Shop> findAllByOwnerIdInOrderByCreatedAtAsc(@Param("ownerIds") Collection<Long> ownerIds);
}
