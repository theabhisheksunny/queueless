package com.queueless.repository;

import com.queueless.entity.Business;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessRepository extends JpaRepository<Business, Long> {
    List<Business> findAllByOwnerIdOrderByCreatedAtAsc(Long ownerId);
    Optional<Business> findByIdAndOwnerId(Long id, Long ownerId);
    List<Business> findAllByOwnerIdInOrderByCreatedAtAsc(Collection<Long> ownerIds);
}
