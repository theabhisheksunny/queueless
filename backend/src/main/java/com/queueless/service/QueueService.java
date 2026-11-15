package com.queueless.service;

import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.model.CustomerPositionInfo;
import com.queueless.model.QueueCustomerSummary;
import com.queueless.model.ShopActiveQueue;
import com.queueless.repository.QueueCustomerRepository;
import com.queueless.repository.QueueSessionRepository;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);
    private static final String ALPHANUM = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShopRepository shopRepository;
    private final QueueSessionRepository sessionRepository;
    private final QueueCustomerRepository customerRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ActiveQueueManager activeQueueManager;
    private final SseEmitterService sseEmitterService;

    public QueueService(ShopRepository shopRepository,
                        QueueSessionRepository sessionRepository,
                        QueueCustomerRepository customerRepository,
                        SubscriptionRepository subscriptionRepository,
                        ActiveQueueManager activeQueueManager,
                        SseEmitterService sseEmitterService) {
        this.shopRepository = shopRepository;
        this.sessionRepository = sessionRepository;
        this.customerRepository = customerRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.activeQueueManager = activeQueueManager;
        this.sseEmitterService = sseEmitterService;
    }

    /**
     * Retrieves or initializes today's queue session for a shop.
     */
    @Transactional
    public ShopActiveQueue getOrInitializeQueue(Long shopId) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + shopId));

        ShopActiveQueue existingMemoryQueue = activeQueueManager.getShopQueue(shopId);
        if (existingMemoryQueue != null && existingMemoryQueue.getSessionId() != null) {
            return existingMemoryQueue;
        }

        // Look for today's active or existing session
        LocalDate today = LocalDate.now();
        QueueSession session = sessionRepository.findTopByShopIdAndStatusOrderByStartedAtDesc(shopId, QueueSessionStatus.OPEN)
                .orElseGet(() -> sessionRepository.findTopByShopIdAndSessionDateOrderByStartedAtDesc(shopId, today)
                        .orElse(null));

        List<QueueCustomer> existingCustomers = null;
        if (session != null) {
            existingCustomers = customerRepository.findByQueueSessionIdOrderByTokenSequenceAsc(session.getId());
        }

        return activeQueueManager.getOrCreateShopQueue(shop, session, existingCustomers);
    }

    /**
     * Starts a new queue session or resumes an open session.
     */
    @Transactional
    public QueueDashboardResponse startQueue(Long shopId) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found"));

        LocalDate today = LocalDate.now();
        // Check if an open session already exists
        QueueSession session = sessionRepository.findTopByShopIdAndStatusOrderByStartedAtDesc(shopId, QueueSessionStatus.OPEN)
                .orElse(null);

        if (session == null) {
            // Check if there was a closed session for today, or create a brand new one
            String publicQueueId = generatePublicQueueId();
            session = new QueueSession(shop, today, publicQueueId);
            session = sessionRepository.save(session);

            activeQueueManager.resetQueueForNewSession(shopId, session.getId(), publicQueueId);
            activeQueueManager.getOrCreateShopQueue(shop, session, List.of());
        } else {
            // Re-open if closed
            session.setStatus(QueueSessionStatus.OPEN);
            session = sessionRepository.save(session);
            activeQueueManager.setQueueStatus(shopId, true);
        }

        notifyQueueUpdated(shopId);
        return getDashboard(shopId);
    }

    /**
     * Closes the active queue session.
     */
    @Transactional
    public QueueDashboardResponse closeQueue(Long shopId) {
        QueueSession session = sessionRepository.findTopByShopIdAndStatusOrderByStartedAtDesc(shopId, QueueSessionStatus.OPEN)
                .orElse(null);

        if (session != null) {
            session.setStatus(QueueSessionStatus.CLOSED);
            session.setClosedAt(LocalDateTime.now());
            ShopActiveQueue active = activeQueueManager.getShopQueue(shopId);
            if (active != null) {
                session.setTokenCounter(active.getTotalCount());
            }
            sessionRepository.save(session);
        }

        activeQueueManager.setQueueStatus(shopId, false);
        notifyQueueUpdated(shopId);
        return getDashboard(shopId);
    }

    /**
     * Customer joins queue through QR code scan.
     */
    @Transactional
    public CustomerJoinResponse customerJoinQueue(String publicQueueId, CustomerJoinRequest request) {
        QueueSession session = sessionRepository.findByPublicQueueId(publicQueueId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid Queue QR code or link."));

        if (session.getStatus() != QueueSessionStatus.OPEN) {
            throw new IllegalStateException("Queue is currently closed. Please check back later or ask the shop owner.");
        }

        Shop shop = session.getShop();
        Long shopId = shop.getId();
        ShopActiveQueue activeQueue = getOrInitializeQueue(shopId);

        if (!activeQueue.isOpen()) {
            throw new IllegalStateException("Queue is currently closed.");
        }

        // Allocate sequential token safely
        int nextSequence = activeQueue.allocateNextSequence();
        String tokenNumber = ShopActiveQueue.formatTokenNumber(nextSequence);
        String trackingToken = UUID.randomUUID().toString().replace("-", "");

        // Persist to database
        QueueCustomer customer = new QueueCustomer(
                session,
                tokenNumber,
                nextSequence,
                request.getCustomerName().trim(),
                request.getCustomerPhone() != null ? request.getCustomerPhone().trim() : null,
                trackingToken,
                AddedBy.QR_CODE
        );
        customer = customerRepository.save(customer);

        // Add to active memory queue
        activeQueueManager.addCustomerToQueue(shopId, customer);

        // Broadcast to Owner and notify tracking
        notifyQueueUpdated(shopId);

        CustomerPositionInfo positionInfo = activeQueue.getCustomerPositionInfo(trackingToken);

        return new CustomerJoinResponse(
                tokenNumber,
                trackingToken,
                shop.getName(),
                positionInfo != null ? positionInfo.getPeopleAhead() : 0,
                positionInfo != null ? positionInfo.getEstimatedWaitMinutes() : 0,
                CustomerQueueStatus.WAITING
        );
    }

    /**
     * Shop owner manually adds customer from dashboard without QR.
     */
    @Transactional
    public QueueDashboardResponse manualAddCustomer(Long shopId, CustomerManualAddRequest request) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found"));

        QueueSession session = sessionRepository.findTopByShopIdAndStatusOrderByStartedAtDesc(shopId, QueueSessionStatus.OPEN)
                .orElseGet(() -> {
                    // Automatically start a session if none is open
                    String newPublicId = generatePublicQueueId();
                    QueueSession newSession = new QueueSession(shop, LocalDate.now(), newPublicId);
                    return sessionRepository.save(newSession);
                });

        ShopActiveQueue activeQueue = getOrInitializeQueue(shopId);
        activeQueue.setOpen(true);
        activeQueue.setSessionId(session.getId());
        activeQueue.setPublicQueueId(session.getPublicQueueId());

        int nextSequence = activeQueue.allocateNextSequence();
        String tokenNumber = ShopActiveQueue.formatTokenNumber(nextSequence);
        String trackingToken = UUID.randomUUID().toString().replace("-", "");

        QueueCustomer customer = new QueueCustomer(
                session,
                tokenNumber,
                nextSequence,
                request.getCustomerName().trim(),
                request.getCustomerPhone() != null ? request.getCustomerPhone().trim() : null,
                trackingToken,
                AddedBy.MANUAL_OWNER
        );
        customer = customerRepository.save(customer);

        activeQueueManager.addCustomerToQueue(shopId, customer);

        notifyQueueUpdated(shopId);
        return getDashboard(shopId);
    }

    /**
     * Calls the next customer in FIFO waiting order.
     * Note requirement: "Do not automatically complete the current customer when the next customer is called."
     */
    @Transactional
    public QueueDashboardResponse callNextCustomer(Long shopId) {
        ShopActiveQueue activeQueue = getOrInitializeQueue(shopId);
        List<QueueCustomerSummary> snapshot = activeQueue.getCustomerSnapshot();

        QueueCustomerSummary nextWaiting = null;
        for (QueueCustomerSummary c : snapshot) {
            if (c.getStatus() == CustomerQueueStatus.WAITING) {
                nextWaiting = c;
                break;
            }
        }

        if (nextWaiting == null) {
            // Also check if any SKIPPED customer can be recalled
            for (QueueCustomerSummary c : snapshot) {
                if (c.getStatus() == CustomerQueueStatus.SKIPPED) {
                    nextWaiting = c;
                    break;
                }
            }
        }

        if (nextWaiting != null) {
            updateCustomerStatus(shopId, nextWaiting.getId(), CustomerQueueStatus.CALLED);
        }

        return getDashboard(shopId);
    }

    /**
     * Updates an existing customer's status (CALLED, SERVING, COMPLETED, SKIPPED, NO_SHOW, CANCELLED).
     */
    @Transactional
    public QueueDashboardResponse updateCustomerStatus(Long shopId, Long customerId, CustomerQueueStatus newStatus) {
        QueueCustomer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found with ID: " + customerId));

        customer.setStatus(newStatus);
        LocalDateTime now = LocalDateTime.now();
        if (newStatus == CustomerQueueStatus.CALLED) {
            customer.setCalledAt(now);
        } else if (newStatus == CustomerQueueStatus.SERVING) {
            customer.setServedAt(now);
        } else if (newStatus == CustomerQueueStatus.COMPLETED) {
            customer.setCompletedAt(now);
        }
        customerRepository.save(customer);

        activeQueueManager.updateCustomerStatus(shopId, customerId, newStatus);

        notifyQueueUpdated(shopId);
        return getDashboard(shopId);
    }

    /**
     * Edit customer name and phone.
     */
    @Transactional
    public QueueDashboardResponse editCustomer(Long shopId, Long customerId, CustomerEditRequest request) {
        QueueCustomer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

        customer.setCustomerName(request.getCustomerName().trim());
        customer.setCustomerPhone(request.getCustomerPhone() != null ? request.getCustomerPhone().trim() : null);
        customerRepository.save(customer);

        activeQueueManager.editCustomer(shopId, customerId, customer.getCustomerName(), customer.getCustomerPhone());

        notifyQueueUpdated(shopId);
        return getDashboard(shopId);
    }

    /**
     * Remove customer from active queue (marks CANCELLED).
     */
    @Transactional
    public QueueDashboardResponse removeCustomer(Long shopId, Long customerId) {
        return updateCustomerStatus(shopId, customerId, CustomerQueueStatus.CANCELLED);
    }

    /**
     * Customer leaves queue via their private tracking link.
     */
    @Transactional
    public CustomerPositionInfo customerLeaveQueue(String trackingToken) {
        QueueCustomer customer = customerRepository.findByTrackingToken(trackingToken)
                .orElseThrow(() -> new IllegalArgumentException("Token not found"));

        if (customer.getStatus() == CustomerQueueStatus.WAITING || customer.getStatus() == CustomerQueueStatus.CALLED) {
            customer.setStatus(CustomerQueueStatus.LEFT);
            customerRepository.save(customer);

            Long shopId = customer.getQueueSession().getShop().getId();
            activeQueueManager.updateCustomerStatus(shopId, customer.getId(), CustomerQueueStatus.LEFT);

            notifyQueueUpdated(shopId);
        }

        return activeQueueManager.getCustomerPosition(trackingToken);
    }

    /**
     * Rejoining once if left/cancelled (optional feature specified in section 10).
     */
    @Transactional
    public CustomerPositionInfo customerRejoinQueue(String trackingToken) {
        QueueCustomer customer = customerRepository.findByTrackingToken(trackingToken)
                .orElseThrow(() -> new IllegalArgumentException("Token not found"));

        if (customer.getStatus() == CustomerQueueStatus.LEFT || customer.getStatus() == CustomerQueueStatus.SKIPPED) {
            customer.setStatus(CustomerQueueStatus.WAITING);
            customerRepository.save(customer);

            Long shopId = customer.getQueueSession().getShop().getId();
            activeQueueManager.updateCustomerStatus(shopId, customer.getId(), CustomerQueueStatus.WAITING);

            notifyQueueUpdated(shopId);
        }

        return activeQueueManager.getCustomerPosition(trackingToken);
    }

    /**
     * Fetches public tracking information for customer tracking page.
     */
    @Transactional(readOnly = true)
    public CustomerPositionInfo getCustomerTrackingInfo(String trackingToken) {
        CustomerPositionInfo info = activeQueueManager.getCustomerPosition(trackingToken);
        if (info != null) {
            return info;
        }

        // DB fallback
        QueueCustomer customer = customerRepository.findByTrackingToken(trackingToken)
                .orElseThrow(() -> new IllegalArgumentException("Tracking token not found"));

        QueueSession session = customer.getQueueSession();
        Shop shop = session.getShop();

        // Calculate people ahead
        long peopleAhead = 0;
        if (customer.getStatus() == CustomerQueueStatus.WAITING) {
            peopleAhead = customerRepository.countByQueueSessionIdAndStatusAndTokenSequenceLessThan(
                    session.getId(), CustomerQueueStatus.WAITING, customer.getTokenSequence());
        }

        // Current serving (DB fallback - only strict SERVING status)
        List<QueueCustomer> servingList = customerRepository.findByQueueSessionIdAndStatusOrderByTokenSequenceAsc(
                session.getId(), CustomerQueueStatus.SERVING);
        String servingToken = "-";
        if (!servingList.isEmpty()) {
            if (servingList.size() == 1) {
                servingToken = servingList.get(0).getTokenNumber();
            } else {
                servingToken = servingList.stream()
                        .map(QueueCustomer::getTokenNumber)
                        .collect(Collectors.joining(", "));
            }
        }
        int waitTime = (int) peopleAhead * shop.getAvgServiceDurationMinutes();

        return new CustomerPositionInfo(
                shop.getName(),
                customer.getTokenNumber(),
                servingToken,
                (int) peopleAhead,
                waitTime,
                customer.getStatus(),
                session.getStatus() == QueueSessionStatus.OPEN,
                customer.getTrackingToken(),
                customer.getJoinedAt()
        );
    }

    /**
     * Generates complete Owner Dashboard view.
     */
    @Transactional(readOnly = true)
    public QueueDashboardResponse getDashboard(Long shopId) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + shopId));

        ShopActiveQueue activeQueue = getOrInitializeQueue(shopId);

        QueueDashboardResponse response = new QueueDashboardResponse();
        response.setShopId(shop.getId());
        response.setShopName(shop.getName());
        response.setCategory(shop.getCategory());
        response.setAvgServiceDurationMinutes(shop.getAvgServiceDurationMinutes());

        response.setSessionId(activeQueue.getSessionId());
        response.setPublicQueueId(activeQueue.getPublicQueueId());
        response.setPublicJoinUrl(activeQueue.getPublicQueueId() != null ? "/join/" + activeQueue.getPublicQueueId() : null);
        response.setQueueOpen(activeQueue.isOpen());

        response.setTotalCustomers(activeQueue.getTotalCount());
        response.setWaitingCustomers(activeQueue.getWaitingCount());

        List<QueueCustomerSummary> servingList = activeQueue.getCurrentlyServingCustomers();
        response.setCurrentlyServingCustomers(servingList);
        if (!servingList.isEmpty()) {
            if (servingList.size() == 1) {
                response.setCurrentlyServingToken(servingList.get(0).getTokenNumber());
                response.setCurrentlyServingName(servingList.get(0).getCustomerName());
            } else {
                response.setCurrentlyServingToken(servingList.stream()
                        .map(QueueCustomerSummary::getTokenNumber)
                        .collect(Collectors.joining(", ")));
                response.setCurrentlyServingName(servingList.stream()
                        .map(QueueCustomerSummary::getCustomerName)
                        .collect(Collectors.joining(", ")));
            }
        } else {
            response.setCurrentlyServingToken(null);
            response.setCurrentlyServingName(null);
        }

        response.setCustomers(activeQueue.getCustomerSnapshot());

        // Subscription info
        Subscription sub = subscriptionRepository.findTopByShopIdOrderByEndDateDesc(shopId).orElse(null);
        if (sub != null && sub.getStatus() == SubscriptionStatus.ACTIVE && sub.getEndDate() != null) {
            response.setSubscriptionStatus(sub.getStatus().name());
            response.setSubscriptionEndDate(sub.getEndDate());
            response.setSubscriptionDaysRemaining(Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), sub.getEndDate())));
        } else {
            response.setSubscriptionStatus("EXPIRED");
            response.setSubscriptionDaysRemaining(0);
        }

        return response;
    }

    /**
     * Broadcasts real-time events to both owner and active customer tracking listeners.
     */
    public void notifyQueueUpdated(Long shopId) {
        boolean hasOwner = sseEmitterService.hasOwnerEmitters(shopId);
        if (hasOwner) {
            QueueDashboardResponse dashboard = getDashboard(shopId);
            sseEmitterService.notifyOwner(shopId, "QUEUE_UPDATED", dashboard);
        }

        // Update active customers who have open SSE connections
        ShopActiveQueue activeQueue = activeQueueManager.getShopQueue(shopId);
        if (activeQueue != null) {
            for (QueueCustomerSummary customer : activeQueue.getCustomerSnapshot()) {
                if (customer.getTrackingToken() != null && sseEmitterService.hasCustomerEmitters(customer.getTrackingToken())) {
                    CustomerPositionInfo posInfo = activeQueue.getCustomerPositionInfo(customer.getTrackingToken());
                    if (posInfo != null) {
                        sseEmitterService.notifyCustomer(customer.getTrackingToken(), "POSITION_UPDATED", posInfo);
                    }
                }
            }
        }
    }

    private String generatePublicQueueId() {
        StringBuilder sb = new StringBuilder("QS-");
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }
}
