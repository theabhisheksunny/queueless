package com.queueless.repository;

import com.queueless.entity.BusinessCategoryChangeRequest;
import com.queueless.entity.CategoryChangeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessCategoryChangeRequestRepository extends JpaRepository<BusinessCategoryChangeRequest, Long> {

    List<BusinessCategoryChangeRequest> findByShopIdOrderByCreatedAtDesc(Long shopId);

    Optional<BusinessCategoryChangeRequest> findTopByShopIdOrderByCreatedAtDesc(Long shopId);

    boolean existsByShopIdAndStatusIn(Long shopId, Collection<CategoryChangeStatus> statuses);

    boolean existsByPaymentReference(String paymentReference);

    boolean existsByPaymentReferenceAndIdNot(String paymentReference, Long id);

    List<BusinessCategoryChangeRequest> findAllByOrderByCreatedAtDesc();
}
