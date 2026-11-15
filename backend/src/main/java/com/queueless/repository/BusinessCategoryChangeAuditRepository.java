package com.queueless.repository;

import com.queueless.entity.BusinessCategoryChangeAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BusinessCategoryChangeAuditRepository extends JpaRepository<BusinessCategoryChangeAudit, Long> {

    List<BusinessCategoryChangeAudit> findByShopIdOrderByApprovedAtDesc(Long shopId);

    List<BusinessCategoryChangeAudit> findAllByOrderByApprovedAtDesc();
}
