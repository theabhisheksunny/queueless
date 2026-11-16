package com.queueless.service;

import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.event.QueueEvent;
import com.queueless.exception.TokenExpiredException;
import com.queueless.model.CustomerPositionInfo;
import com.queueless.model.QueueCustomerSummary;
import com.queueless.model.ShopActiveQueue;
import com.queueless.repository.QueueCustomerRepository;
import com.queueless.repository.QueueSessionRepository;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
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
    private final EntitlementService entitlementService;
    private ApplicationEventPublisher eventPublisher;

    @Value("${queueless.token.completed-expiration-minutes:15}")
    private long completedExpirationMinutes = 15;

    private Clock clock = Clock.systemDefaultZone();

    public QueueService(ShopRepository shopRepository,
                        QueueSessionRepository sessionRepository,
                        QueueCustomerRepository customerRepository,
                        SubscriptionRepository subscriptionRepository,
                        ActiveQueueManager activeQueueManager,
                        SseEmitterService sseEmitterService,
                        EntitlementService entitlementService,
                        @Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.shopRepository = shopRepository;
        this.sessionRepository = sessionRepository;
        this.customerRepository = customerRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.activeQueueManager = activeQueueManager;
        this.sseEmitterService = sseEmitterService;
        this.entitlementService = entitlementService;
        this.eventPublisher = eventPublisher;
    }

    @Autowired(required = false)
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Autowired(required = false)
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    public Clock getClock() {
        return this.clock;
    }

    public void setCompletedExpirationMinutes(long completedExpirationMinutes) {
        this.completedExpirationMinutes = completedExpirationMinutes;
    }

    public long getCompletedExpirationMinutes() {
        return this.completedExpirationMinutes;
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
        if (eventPublisher != null) {
            Shop shop = shopRepository.findById(shopId).orElse(null);
            eventPublisher.publishEvent(QueueEvent.forQueueClosed(shopId, shop != null ? shop.getName() : ""));
        }
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

        if (!entitlementService.hasActiveEntitlement(shopId)) {
            throw new IllegalStateException("Shop queue is temporarily unavailable due to subscription status.");
        }

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

        if (eventPublisher != null) {
            eventPublisher.publishEvent(QueueEvent.forCustomer(
                    QueueEvent.Type.CUSTOMER_JOINED,
                    shopId,
                    shop.getName(),
                    trackingToken,
                    tokenNumber,
                    positionInfo != null ? positionInfo.getPeopleAhead() : 0,
                    positionInfo != null ? positionInfo.getEstimatedWaitMinutes() : 0
            ));
        }

        return new CustomerJoinResponse(
                tokenNumber,
                trackingToken,
                shop.getName(),
                shop.getCategory(),
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
     * Checks if a completed token has exceeded the 15-minute expiration period.
     */
    public boolean isTokenExpired(LocalDateTime completedAt, CustomerQueueStatus status) {
        if (status != CustomerQueueStatus.COMPLETED || completedAt == null) {
            return false;
        }
        LocalDateTime expiresAt = completedAt.plusMinutes(this.completedExpirationMinutes);
        LocalDateTime now = LocalDateTime.now(this.clock);
        return !now.isBefore(expiresAt);
    }

    /**
     * Validates that a public tracking token exists and has not expired.
     */
    public void validateTrackingTokenNotExpired(String trackingToken) {
        if (trackingToken == null || trackingToken.isBlank()) {
            throw new IllegalArgumentException("Tracking token is required");
        }

        // 1. Check in-memory active queue
        ShopActiveQueue queue = activeQueueManager.getShopQueueByTrackingToken(trackingToken);
        if (queue != null) {
            QueueCustomerSummary summary = queue.findByTrackingToken(trackingToken);
            if (summary != null) {
                if (summary.getStatus() == CustomerQueueStatus.COMPLETED && isTokenExpired(summary.getCompletedAt(), summary.getStatus())) {
                    sseEmitterService.closeCustomerEmitters(trackingToken);
                    throw new TokenExpiredException("Your token has expired. Your service was already completed.");
                }
                return;
            }
        }

        // 2. Check DB fallback
        QueueCustomer customer = customerRepository.findByTrackingToken(trackingToken)
                .orElseThrow(() -> new IllegalArgumentException("Tracking token not found"));

        if (customer.getStatus() == CustomerQueueStatus.COMPLETED && isTokenExpired(customer.getCompletedAt(), customer.getStatus())) {
            sseEmitterService.closeCustomerEmitters(trackingToken);
            throw new TokenExpiredException("Your token has expired. Your service was already completed.");
        }
    }

    /**
     * Updates an existing customer's status (CALLED, SERVING, COMPLETED, SKIPPED, NO_SHOW, CANCELLED).
     */
    @Transactional
    public QueueDashboardResponse updateCustomerStatus(Long shopId, Long customerId, CustomerQueueStatus newStatus) {
        QueueCustomer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found with ID: " + customerId));

        customer.setStatus(newStatus);
        LocalDateTime now = LocalDateTime.now(this.clock);
        if (newStatus == CustomerQueueStatus.CALLED) {
            customer.setCalledAt(now);
        } else if (newStatus == CustomerQueueStatus.SERVING) {
            customer.setServedAt(now);
        } else if (newStatus == CustomerQueueStatus.COMPLETED) {
            // Ensure repeated completion requests do not unexpectedly reset the expiration timestamp
            if (customer.getCompletedAt() == null) {
                customer.setCompletedAt(now);
            }
        }
        customerRepository.save(customer);

        activeQueueManager.updateCustomerStatus(shopId, customerId, newStatus, customer.getCompletedAt());

        // Publish push domain events
        if (eventPublisher != null) {
            Shop shop = customer.getQueueSession().getShop();
            String shopName = shop != null ? shop.getName() : "";
            if (newStatus == CustomerQueueStatus.CALLED) {
                eventPublisher.publishEvent(QueueEvent.forCustomer(
                        QueueEvent.Type.TOKEN_CALLED, shopId, shopName, customer.getTrackingToken(), customer.getTokenNumber(), 0, 0
                ));
            } else if (newStatus == CustomerQueueStatus.SERVING) {
                eventPublisher.publishEvent(QueueEvent.forCustomer(
                        QueueEvent.Type.SERVICE_STARTED, shopId, shopName, customer.getTrackingToken(), customer.getTokenNumber(), 0, 0
                ));
            } else if (newStatus == CustomerQueueStatus.COMPLETED) {
                eventPublisher.publishEvent(QueueEvent.forCustomer(
                        QueueEvent.Type.SERVICE_COMPLETED, shopId, shopName, customer.getTrackingToken(), customer.getTokenNumber(), 0, 0
                ));
            } else if (newStatus == CustomerQueueStatus.CANCELLED) {
                eventPublisher.publishEvent(QueueEvent.forCustomer(
                        QueueEvent.Type.CUSTOMER_REMOVED, shopId, shopName, customer.getTrackingToken(), customer.getTokenNumber(), 0, 0
                ));
            } else if (newStatus == CustomerQueueStatus.NO_SHOW) {
                eventPublisher.publishEvent(QueueEvent.forCustomer(
                        QueueEvent.Type.CUSTOMER_NO_SHOW, shopId, shopName, customer.getTrackingToken(), customer.getTokenNumber(), 0, 0
                ));
            }
        }

        // If completed, schedule customer SSE emitter closure after expiration duration
        if (newStatus == CustomerQueueStatus.COMPLETED && customer.getTrackingToken() != null) {
            sseEmitterService.scheduleEmitterExpiration(customer.getTrackingToken(), this.completedExpirationMinutes * 60 * 1000L);
        }

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
        validateTrackingTokenNotExpired(trackingToken);

        QueueCustomer customer = customerRepository.findByTrackingToken(trackingToken)
                .orElseThrow(() -> new IllegalArgumentException("Token not found"));

        if (customer.getStatus() == CustomerQueueStatus.WAITING || customer.getStatus() == CustomerQueueStatus.CALLED) {
            customer.setStatus(CustomerQueueStatus.LEFT);
            customerRepository.save(customer);

            Long shopId = customer.getQueueSession().getShop().getId();
            activeQueueManager.updateCustomerStatus(shopId, customer.getId(), CustomerQueueStatus.LEFT);

            if (eventPublisher != null) {
                Shop shop = customer.getQueueSession().getShop();
                eventPublisher.publishEvent(QueueEvent.forCustomer(
                        QueueEvent.Type.CUSTOMER_LEFT, shopId, shop != null ? shop.getName() : "", customer.getTrackingToken(), customer.getTokenNumber(), 0, 0
                ));
            }

            notifyQueueUpdated(shopId);
        }

        return getCustomerTrackingInfo(trackingToken);
    }

    /**
     * Rejoining once if left/cancelled (optional feature specified in section 10).
     * Completed tokens must never be reactivated.
     */
    @Transactional
    public CustomerPositionInfo customerRejoinQueue(String trackingToken) {
        validateTrackingTokenNotExpired(trackingToken);

        QueueCustomer customer = customerRepository.findByTrackingToken(trackingToken)
                .orElseThrow(() -> new IllegalArgumentException("Token not found"));

        if (customer.getStatus() == CustomerQueueStatus.COMPLETED) {
            throw new IllegalStateException("Completed tokens cannot rejoin the queue. Please scan QR code or ask shop owner for a new token.");
        }

        if (customer.getStatus() == CustomerQueueStatus.LEFT || customer.getStatus() == CustomerQueueStatus.SKIPPED) {
            customer.setStatus(CustomerQueueStatus.WAITING);
            customerRepository.save(customer);

            Long shopId = customer.getQueueSession().getShop().getId();
            activeQueueManager.updateCustomerStatus(shopId, customer.getId(), CustomerQueueStatus.WAITING);

            notifyQueueUpdated(shopId);
        }

        return getCustomerTrackingInfo(trackingToken);
    }

    /**
     * Fetches public tracking information for customer tracking page.
     * Enforces the 15-minute completed token expiration policy.
     */
    @Transactional(readOnly = true)
    public CustomerPositionInfo getCustomerTrackingInfo(String trackingToken) {
        validateTrackingTokenNotExpired(trackingToken);

        CustomerPositionInfo info = activeQueueManager.getCustomerPosition(trackingToken);
        if (info != null) {
            if (info.getCompletedAt() != null) {
                info.setExpiresAt(info.getCompletedAt().plusMinutes(this.completedExpirationMinutes));
            }
            return info;
        }

        // DB fallback
        QueueCustomer customer = customerRepository.findByTrackingToken(trackingToken)
                .orElseThrow(() -> new IllegalArgumentException("Tracking token not found"));

        if (customer.getStatus() == CustomerQueueStatus.COMPLETED && isTokenExpired(customer.getCompletedAt(), customer.getStatus())) {
            sseEmitterService.closeCustomerEmitters(trackingToken);
            throw new TokenExpiredException("Your token has expired. Your service was already completed.");
        }

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

        CustomerPositionInfo posInfo = new CustomerPositionInfo(
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
        posInfo.setCategory(shop.getCategory());
        posInfo.setCompletedAt(customer.getCompletedAt());
        if (customer.getCompletedAt() != null) {
            posInfo.setExpiresAt(customer.getCompletedAt().plusMinutes(this.completedExpirationMinutes));
        }
        return posInfo;
    }

    /**
     * Fetches public queue overview info for the customer join page.
     */
    @Transactional(readOnly = true)
    public PublicQueueInfoResponse getPublicQueueInfo(String publicQueueId) {
        QueueSession session = sessionRepository.findByPublicQueueId(publicQueueId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid Queue QR code or link."));
        Shop shop = session.getShop();
        ShopActiveQueue activeQueue = getOrInitializeQueue(shop.getId());
        List<QueueCustomerSummary> servingList = activeQueue.getCurrentlyServingCustomers();
        String servingToken = "-";
        if (!servingList.isEmpty()) {
            servingToken = servingList.stream()
                    .map(QueueCustomerSummary::getTokenNumber)
                    .collect(Collectors.joining(", "));
        }
        return new PublicQueueInfoResponse(
                shop.getName(),
                shop.getCategory(),
                activeQueue.isOpen(),
                activeQueue.getWaitingCount(),
                servingToken
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
                    if (isTokenExpired(customer.getCompletedAt(), customer.getStatus())) {
                        sseEmitterService.closeCustomerEmitters(customer.getTrackingToken());
                        continue;
                    }
                    CustomerPositionInfo posInfo = activeQueue.getCustomerPositionInfo(customer.getTrackingToken());
                    if (posInfo != null) {
                        if (posInfo.getCompletedAt() != null) {
                            posInfo.setExpiresAt(posInfo.getCompletedAt().plusMinutes(this.completedExpirationMinutes));
                        }
                        sseEmitterService.notifyCustomer(customer.getTrackingToken(), "POSITION_UPDATED", posInfo);
                    }
                }
            }
        }

        // Publish milestone push updates for waiting customers (milestones: 5, 3, 2, 1 ahead)
        if (activeQueue != null && eventPublisher != null) {
            Shop shop = shopRepository.findById(shopId).orElse(null);
            String shopName = shop != null ? shop.getName() : "";
            int avgDuration = shop != null ? shop.getAvgServiceDurationMinutes() : 10;

            for (QueueCustomerSummary customer : activeQueue.getCustomerSnapshot()) {
                if (customer.getStatus() == CustomerQueueStatus.WAITING && customer.getTrackingToken() != null) {
                    CustomerPositionInfo posInfo = activeQueue.getCustomerPositionInfo(customer.getTrackingToken());
                    if (posInfo != null) {
                        int ahead = posInfo.getPeopleAhead();
                        if (ahead == 5 || ahead == 3 || ahead == 2 || ahead == 1) {
                            if (customer.getLastPushedPosition() == null || customer.getLastPushedPosition() != ahead) {
                                customer.setLastPushedPosition(ahead);
                                eventPublisher.publishEvent(QueueEvent.forCustomer(
                                        QueueEvent.Type.POSITION_MILESTONE,
                                        shopId,
                                        shopName,
                                        customer.getTrackingToken(),
                                        customer.getTokenNumber(),
                                        ahead,
                                        ahead * avgDuration
                                ));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Closes SSE emitters for any completed tokens that have passed the expiration threshold.
     */
    public void closeExpiredCustomerEmitters() {
        for (ShopActiveQueue queue : activeQueueManager.getAllQueues()) {
            for (QueueCustomerSummary c : queue.getCustomerSnapshot()) {
                if (c.getTrackingToken() != null && isTokenExpired(c.getCompletedAt(), c.getStatus())) {
                    sseEmitterService.closeCustomerEmitters(c.getTrackingToken());
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
