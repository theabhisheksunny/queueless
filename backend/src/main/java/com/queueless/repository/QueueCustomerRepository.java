package com.queueless.repository;

import com.queueless.entity.CustomerQueueStatus;
import com.queueless.entity.QueueCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface QueueCustomerRepository extends JpaRepository<QueueCustomer, Long> {
    Optional<QueueCustomer> findByTrackingToken(String trackingToken);
    List<QueueCustomer> findByQueueSessionIdOrderByTokenSequenceAsc(Long queueSessionId);
    List<QueueCustomer> findByQueueSessionIdAndStatusOrderByTokenSequenceAsc(Long queueSessionId, CustomerQueueStatus status);
    List<QueueCustomer> findByQueueSessionIdAndStatusInOrderByTokenSequenceAsc(Long queueSessionId, Collection<CustomerQueueStatus> statuses);
    Optional<QueueCustomer> findFirstByQueueSessionIdAndStatusOrderByTokenSequenceAsc(Long queueSessionId, CustomerQueueStatus status);
    long countByQueueSessionIdAndStatus(Long queueSessionId, CustomerQueueStatus status);
    long countByQueueSessionIdAndStatusAndTokenSequenceLessThan(Long queueSessionId, CustomerQueueStatus status, Integer tokenSequence);
}
