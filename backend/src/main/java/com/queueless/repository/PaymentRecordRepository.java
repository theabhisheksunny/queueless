package com.queueless.repository;

import com.queueless.entity.PaymentRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, Long> {
    List<PaymentRecord> findAllByOwnerIdOrderByCreatedAtDesc(Long ownerId);
    List<PaymentRecord> findAllByStatusOrderByCreatedAtDesc(String status);
    Page<PaymentRecord> findAllByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    Page<PaymentRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Optional<PaymentRecord> findByPaymentReference(String paymentReference);
    boolean existsByPaymentReferenceAndStatus(String paymentReference, String status);
    boolean existsByPaymentReference(String paymentReference);
    long countByStatus(String status);
    List<PaymentRecord> findTop5ByStatusOrderByCreatedAtDesc(String status);
    List<PaymentRecord> findTop5ByStatusOrderByReviewedAtDesc(String status);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentRecord p WHERE p.status = :status")
    java.math.BigDecimal sumAmountByStatus(@org.springframework.data.repository.query.Param("status") String status);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT p FROM PaymentRecord p WHERE p.id = :id")
    Optional<PaymentRecord> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
