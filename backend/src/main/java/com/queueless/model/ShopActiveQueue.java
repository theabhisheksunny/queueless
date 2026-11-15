package com.queueless.model;

import com.queueless.entity.CustomerQueueStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe in-memory representation of an active shop queue session.
 * Uses an ArrayList for sequential in-memory queue ordering and ConcurrentHashMap for active lookup.
 */
public class ShopActiveQueue {

    private final Long shopId;
    private final String shopName;
    private final int avgServiceDurationMinutes;
    private Long sessionId;
    private String publicQueueId;
    private volatile boolean open;

    private final AtomicInteger sequenceCounter = new AtomicInteger(0);

    // Explicit architectural requirement: ArrayList for active in-memory queue representation
    private final List<QueueCustomerSummary> customerList = new ArrayList<>();

    // Explicit architectural requirement: ConcurrentHashMap for active queue lookup
    private final ConcurrentHashMap<Long, QueueCustomerSummary> idLookup = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, QueueCustomerSummary> trackingTokenLookup = new ConcurrentHashMap<>();

    public ShopActiveQueue(Long shopId, String shopName, int avgServiceDurationMinutes,
                           Long sessionId, String publicQueueId, boolean open, int initialCounter) {
        this.shopId = shopId;
        this.shopName = shopName;
        this.avgServiceDurationMinutes = avgServiceDurationMinutes > 0 ? avgServiceDurationMinutes : 15;
        this.sessionId = sessionId;
        this.publicQueueId = publicQueueId;
        this.open = open;
        this.sequenceCounter.set(initialCounter);
    }

    public Long getShopId() {
        return shopId;
    }

    public String getShopName() {
        return shopName;
    }

    public int getAvgServiceDurationMinutes() {
        return avgServiceDurationMinutes;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public synchronized void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getPublicQueueId() {
        return publicQueueId;
    }

    public synchronized void setPublicQueueId(String publicQueueId) {
        this.publicQueueId = publicQueueId;
    }

    public boolean isOpen() {
        return open;
    }

    public synchronized void setOpen(boolean open) {
        this.open = open;
    }

    /**
     * Allocates next sequential token in a thread-safe manner (001, 002, 003...).
     */
    public synchronized int allocateNextSequence() {
        return sequenceCounter.incrementAndGet();
    }

    public static String formatTokenNumber(int sequence) {
        if (sequence < 1000) {
            return String.format("%03d", sequence);
        }
        return String.valueOf(sequence);
    }

    /**
     * Adds customer to both the in-memory ArrayList and ConcurrentHashMap lookups.
     */
    public synchronized void addCustomer(QueueCustomerSummary customer) {
        customerList.add(customer);
        if (customer.getId() != null) {
            idLookup.put(customer.getId(), customer);
        }
        if (customer.getTrackingToken() != null) {
            trackingTokenLookup.put(customer.getTrackingToken(), customer);
        }
    }

    /**
     * Updates an existing customer's status in memory.
     */
    public synchronized QueueCustomerSummary updateCustomerStatus(Long customerId, CustomerQueueStatus newStatus) {
        return updateCustomerStatus(customerId, newStatus, null);
    }

    public synchronized QueueCustomerSummary updateCustomerStatus(Long customerId, CustomerQueueStatus newStatus, LocalDateTime completedAtTimestamp) {
        QueueCustomerSummary customer = idLookup.get(customerId);
        if (customer == null) {
            // Fallback scan
            for (QueueCustomerSummary c : customerList) {
                if (c.getId().equals(customerId)) {
                    customer = c;
                    break;
                }
            }
        }

        if (customer != null) {
            customer.setStatus(newStatus);
            LocalDateTime now = LocalDateTime.now();
            if (newStatus == CustomerQueueStatus.CALLED) {
                customer.setCalledAt(now);
            } else if (newStatus == CustomerQueueStatus.SERVING) {
                customer.setServedAt(now);
            } else if (newStatus == CustomerQueueStatus.COMPLETED) {
                // Ensure repeated completion requests do not reset expiration timestamp
                if (customer.getCompletedAt() == null) {
                    customer.setCompletedAt(completedAtTimestamp != null ? completedAtTimestamp : now);
                }
            }
        }
        return customer;
    }

    /**
     * Edit customer name and phone in memory.
     */
    public synchronized QueueCustomerSummary editCustomer(Long customerId, String name, String phone) {
        QueueCustomerSummary customer = idLookup.get(customerId);
        if (customer != null) {
            customer.setCustomerName(name);
            customer.setCustomerPhone(phone);
        }
        return customer;
    }

    /**
     * Remove customer from active list.
     */
    public synchronized QueueCustomerSummary removeCustomer(Long customerId) {
        QueueCustomerSummary target = null;
        for (int i = 0; i < customerList.size(); i++) {
            if (customerList.get(i).getId().equals(customerId)) {
                target = customerList.get(i);
                target.setStatus(CustomerQueueStatus.CANCELLED);
                break;
            }
        }
        return target;
    }

    /**
     * Returns an unmodifiable snapshot of the customer list.
     */
    public synchronized List<QueueCustomerSummary> getCustomerSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(customerList));
    }

    public QueueCustomerSummary findByTrackingToken(String trackingToken) {
        if (trackingToken == null) return null;
        return trackingTokenLookup.get(trackingToken);
    }

    public QueueCustomerSummary findById(Long customerId) {
        if (customerId == null) return null;
        return idLookup.get(customerId);
    }

    /**
     * Returns the customers currently in SERVING status.
     * Note requirement: Do NOT include CALLED customers as currently serving.
     */
    public synchronized List<QueueCustomerSummary> getCurrentlyServingCustomers() {
        List<QueueCustomerSummary> serving = new ArrayList<>();
        for (QueueCustomerSummary c : customerList) {
            if (c.getStatus() == CustomerQueueStatus.SERVING) {
                serving.add(c);
            }
        }
        return Collections.unmodifiableList(serving);
    }

    /**
     * Returns the first customer currently in SERVING status, or null if none.
     */
    public synchronized QueueCustomerSummary getCurrentlyServingCustomer() {
        for (QueueCustomerSummary c : customerList) {
            if (c.getStatus() == CustomerQueueStatus.SERVING) {
                return c;
            }
        }
        return null;
    }

    /**
     * Counts customers currently in WAITING status.
     */
    public synchronized int getWaitingCount() {
        int count = 0;
        for (QueueCustomerSummary c : customerList) {
            if (c.getStatus() == CustomerQueueStatus.WAITING) {
                count++;
            }
        }
        return count;
    }

    public synchronized int getTotalCount() {
        return customerList.size();
    }

    /**
     * Calculates customer's exact position, people ahead, and estimated wait.
     */
    public synchronized CustomerPositionInfo getCustomerPositionInfo(String trackingToken) {
        QueueCustomerSummary target = trackingTokenLookup.get(trackingToken);
        if (target == null) {
            return null;
        }

        List<QueueCustomerSummary> currentServingList = getCurrentlyServingCustomers();
        String servingToken;
        if (currentServingList.isEmpty()) {
            servingToken = "-";
        } else if (currentServingList.size() == 1) {
            servingToken = currentServingList.get(0).getTokenNumber();
        } else {
            servingToken = currentServingList.stream()
                    .map(QueueCustomerSummary::getTokenNumber)
                    .collect(java.util.stream.Collectors.joining(", "));
        }

        int peopleAhead = 0;
        if (target.getStatus() == CustomerQueueStatus.WAITING) {
            for (QueueCustomerSummary c : customerList) {
                if (c.getStatus() == CustomerQueueStatus.WAITING && c.getTokenSequence() < target.getTokenSequence()) {
                    peopleAhead++;
                }
            }
        }

        int estimatedWaitMinutes = peopleAhead * this.avgServiceDurationMinutes;

        CustomerPositionInfo positionInfo = new CustomerPositionInfo(
                this.shopName,
                target.getTokenNumber(),
                servingToken,
                peopleAhead,
                estimatedWaitMinutes,
                target.getStatus(),
                this.open,
                target.getTrackingToken(),
                target.getJoinedAt()
        );
        positionInfo.setCompletedAt(target.getCompletedAt());
        if (target.getCompletedAt() != null) {
            positionInfo.setExpiresAt(target.getCompletedAt().plusMinutes(15));
        }
        return positionInfo;
    }

    /**
     * Clears all customers (e.g. on new day session start).
     */
    public synchronized void resetQueue(Long newSessionId, String newPublicQueueId) {
        this.sessionId = newSessionId;
        this.publicQueueId = newPublicQueueId;
        this.open = true;
        this.sequenceCounter.set(0);
        this.customerList.clear();
        this.idLookup.clear();
        this.trackingTokenLookup.clear();
    }
}
