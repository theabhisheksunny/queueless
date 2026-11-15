package com.queueless.repository;

import com.queueless.entity.TrialEligibilityLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TrialEligibilityLedgerRepository extends JpaRepository<TrialEligibilityLedger, Long> {
    Optional<TrialEligibilityLedger> findByNormalizedPhone(String normalizedPhone);
    boolean existsByNormalizedPhone(String normalizedPhone);
    Optional<TrialEligibilityLedger> findByPhoneHmac(String phoneHmac);
}
