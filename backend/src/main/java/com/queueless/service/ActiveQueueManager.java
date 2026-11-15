package com.queueless.service;

import com.queueless.entity.CustomerQueueStatus;
import com.queueless.entity.QueueCustomer;
import com.queueless.entity.QueueSession;
import com.queueless.entity.QueueSessionStatus;
import com.queueless.entity.Shop;
import com.queueless.model.CustomerPositionInfo;
import com.queueless.model.QueueCustomerSummary;
import com.queueless.model.ShopActiveQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing in-memory active queues across all shops.
 * Complies with architectural requirement:
 * "Use ArrayList for the active in-memory queue representation and
 *  ConcurrentHashMap for active queue lookup, with appropriate synchronization and database transactions."
 */
@Service
public class ActiveQueueManager {

    private static final Logger log = LoggerFactory.getLogger(ActiveQueueManager.class);

    // Active queues indexed by Shop ID
    private final ConcurrentHashMap<Long, ShopActiveQueue> queuesByShopId = new ConcurrentHashMap<>();

    // Quick lookup maps
    private final ConcurrentHashMap<String, Long> shopIdByPublicQueueId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> shopIdByTrackingToken = new ConcurrentHashMap<>();

    /**
     * Initializes or updates the in-memory queue state for a shop.
     */
    public ShopActiveQueue getOrCreateShopQueue(Shop shop, QueueSession activeSession, List<QueueCustomer> existingCustomers) {
        Long shopId = shop.getId();
        return queuesByShopId.compute(shopId, (id, existingQueue) -> {
            boolean isOpen = activeSession != null && activeSession.getStatus() == QueueSessionStatus.OPEN;
            Long sessionId = activeSession != null ? activeSession.getId() : null;
            String publicQueueId = activeSession != null ? activeSession.getPublicQueueId() : null;
            int lastCounter = activeSession != null && activeSession.getTokenCounter() != null ? activeSession.getTokenCounter() : 0;

            ShopActiveQueue queue;
            if (existingQueue == null) {
                queue = new ShopActiveQueue(
                        shop.getId(),
                        shop.getName(),
                        shop.getAvgServiceDurationMinutes(),
                        sessionId,
                        publicQueueId,
                        isOpen,
                        lastCounter
                );
            } else {
                queue = existingQueue;
                queue.setSessionId(sessionId);
                queue.setPublicQueueId(publicQueueId);
                queue.setOpen(isOpen);
            }

            if (publicQueueId != null) {
                shopIdByPublicQueueId.put(publicQueueId, shopId);
            }

            // Populate customers if active
            if (existingCustomers != null && !existingCustomers.isEmpty()) {
                for (QueueCustomer qc : existingCustomers) {
                    QueueCustomerSummary summary = new QueueCustomerSummary(
                            qc.getId(),
                            qc.getTokenNumber(),
                            qc.getTokenSequence(),
                            qc.getCustomerName(),
                            qc.getCustomerPhone(),
                            qc.getTrackingToken(),
                            qc.getStatus(),
                            qc.getAddedBy(),
                            qc.getJoinedAt(),
                            qc.getCalledAt(),
                            qc.getServedAt(),
                            qc.getCompletedAt()
                    );
                    queue.addCustomer(summary);
                    if (qc.getTrackingToken() != null) {
                        shopIdByTrackingToken.put(qc.getTrackingToken(), shopId);
                    }
                }
            }

            return queue;
        });
    }

    public ShopActiveQueue getShopQueue(Long shopId) {
        return queuesByShopId.get(shopId);
    }

    public ShopActiveQueue getShopQueueByPublicId(String publicQueueId) {
        if (publicQueueId == null) return null;
        Long shopId = shopIdByPublicQueueId.get(publicQueueId);
        if (shopId != null) {
            return queuesByShopId.get(shopId);
        }
        return null;
    }

    public ShopActiveQueue getShopQueueByTrackingToken(String trackingToken) {
        if (trackingToken == null) return null;
        Long shopId = shopIdByTrackingToken.get(trackingToken);
        if (shopId != null) {
            return queuesByShopId.get(shopId);
        }
        return null;
    }

    public void mapTrackingTokenToShop(String trackingToken, Long shopId) {
        if (trackingToken != null && shopId != null) {
            shopIdByTrackingToken.put(trackingToken, shopId);
        }
    }

    public void mapPublicQueueIdToShop(String publicQueueId, Long shopId) {
        if (publicQueueId != null && shopId != null) {
            shopIdByPublicQueueId.put(publicQueueId, shopId);
        }
    }

    public void addCustomerToQueue(Long shopId, QueueCustomer customer) {
        ShopActiveQueue queue = queuesByShopId.get(shopId);
        if (queue != null) {
            QueueCustomerSummary summary = new QueueCustomerSummary(
                    customer.getId(),
                    customer.getTokenNumber(),
                    customer.getTokenSequence(),
                    customer.getCustomerName(),
                    customer.getCustomerPhone(),
                    customer.getTrackingToken(),
                    customer.getStatus(),
                    customer.getAddedBy(),
                    customer.getJoinedAt(),
                    customer.getCalledAt(),
                    customer.getServedAt(),
                    customer.getCompletedAt()
            );
            queue.addCustomer(summary);
            mapTrackingTokenToShop(customer.getTrackingToken(), shopId);
        }
    }

    public QueueCustomerSummary updateCustomerStatus(Long shopId, Long customerId, CustomerQueueStatus newStatus) {
        ShopActiveQueue queue = queuesByShopId.get(shopId);
        if (queue != null) {
            return queue.updateCustomerStatus(customerId, newStatus);
        }
        return null;
    }

    public QueueCustomerSummary editCustomer(Long shopId, Long customerId, String name, String phone) {
        ShopActiveQueue queue = queuesByShopId.get(shopId);
        if (queue != null) {
            return queue.editCustomer(customerId, name, phone);
        }
        return null;
    }

    public QueueCustomerSummary removeCustomer(Long shopId, Long customerId) {
        ShopActiveQueue queue = queuesByShopId.get(shopId);
        if (queue != null) {
            return queue.removeCustomer(customerId);
        }
        return null;
    }

    public CustomerPositionInfo getCustomerPosition(String trackingToken) {
        ShopActiveQueue queue = getShopQueueByTrackingToken(trackingToken);
        if (queue != null) {
            return queue.getCustomerPositionInfo(trackingToken);
        }
        return null;
    }

    public void setQueueStatus(Long shopId, boolean isOpen) {
        ShopActiveQueue queue = queuesByShopId.get(shopId);
        if (queue != null) {
            queue.setOpen(isOpen);
        }
    }

    public void resetQueueForNewSession(Long shopId, Long newSessionId, String newPublicQueueId) {
        ShopActiveQueue queue = queuesByShopId.get(shopId);
        if (queue != null) {
            queue.resetQueue(newSessionId, newPublicQueueId);
            mapPublicQueueIdToShop(newPublicQueueId, shopId);
        }
    }
}
