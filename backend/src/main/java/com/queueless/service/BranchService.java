package com.queueless.service;

import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BranchService {

    private static final Logger log = LoggerFactory.getLogger(BranchService.class);
    public static final BigDecimal BRANCH_REGISTRATION_FEE = new BigDecimal("99.00");
    private static final java.time.Duration IDEMPOTENCY_TTL = java.time.Duration.ofHours(1);

    private final ConcurrentHashMap<String, CachedBranchCreation> idempotencyCache = new ConcurrentHashMap<>();

    private record CachedBranchCreation(BranchSummaryResponse response, String requestHash, Instant createdAt) {}

    private String computeRequestHash(CreateBranchRequest request) {
        String raw = (request.getBusinessId() != null ? request.getBusinessId().toString() : "null") + "|" +
                (request.getName() != null ? request.getName().trim().toLowerCase() : "") + "|" +
                (request.getBranchName() != null ? request.getBranchName().trim().toLowerCase() : "") + "|" +
                (request.getCategory() != null ? request.getCategory().trim().toLowerCase() : "") + "|" +
                (request.getAddress() != null ? request.getAddress().trim().toLowerCase() : "") + "|" +
                (request.getPhone() != null ? request.getPhone().trim() : "") + "|" +
                (request.getAvgServiceDurationMinutes() != null ? request.getAvgServiceDurationMinutes().toString() : "15");
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private final BusinessRepository businessRepository;
    private final ShopRepository shopRepository;
    private final UserRepository userRepository;
    private final QueueSessionRepository sessionRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final AuditLogRepository auditLogRepository;
    private final ActiveQueueManager activeQueueManager;
    private final SubscriptionService subscriptionService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public BranchService(BusinessRepository businessRepository,
                         ShopRepository shopRepository,
                         UserRepository userRepository,
                         QueueSessionRepository sessionRepository,
                         PaymentRecordRepository paymentRecordRepository,
                         AuditLogRepository auditLogRepository,
                         ActiveQueueManager activeQueueManager,
                         SubscriptionService subscriptionService) {
        this(businessRepository, shopRepository, userRepository, sessionRepository,
             paymentRecordRepository, auditLogRepository, activeQueueManager, subscriptionService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BranchService(BusinessRepository businessRepository,
                         ShopRepository shopRepository,
                         UserRepository userRepository,
                         QueueSessionRepository sessionRepository,
                         PaymentRecordRepository paymentRecordRepository,
                         AuditLogRepository auditLogRepository,
                         ActiveQueueManager activeQueueManager,
                         SubscriptionService subscriptionService,
                         @org.springframework.beans.factory.annotation.Autowired(required = false) IdempotencyKeyRepository idempotencyKeyRepository) {
        this.businessRepository = businessRepository;
        this.shopRepository = shopRepository;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.auditLogRepository = auditLogRepository;
        this.activeQueueManager = activeQueueManager;
        this.subscriptionService = subscriptionService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Transactional(readOnly = true)
    public List<BusinessResponse> getBusinessesAndBranches(Long ownerId) {
        List<Business> businesses = businessRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerId);
        List<Shop> allShops = shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerId);

        if (businesses.isEmpty() && !allShops.isEmpty()) {
            // Auto-heal if no business was recorded
            Business defaultBiz = new Business(allShops.get(0).getOwner(), allShops.get(0).getName());
            defaultBiz = businessRepository.save(defaultBiz);
            for (Shop s : allShops) {
                if (s.getBusiness() == null) {
                    s.setBusiness(defaultBiz);
                    shopRepository.save(s);
                }
            }
            businesses = List.of(defaultBiz);
        }

        Map<Long, List<BranchSummaryResponse>> branchMap = new HashMap<>();
        for (Shop s : allShops) {
            Long bizId = s.getBusiness() != null ? s.getBusiness().getId() : 0L;
            branchMap.computeIfAbsent(bizId, k -> new ArrayList<>()).add(toBranchSummary(s));
        }

        List<BusinessResponse> responses = new ArrayList<>();
        for (Business b : businesses) {
            List<BranchSummaryResponse> branches = branchMap.getOrDefault(b.getId(), new ArrayList<>());
            responses.add(new BusinessResponse(b.getId(), b.getName(), branches));
        }

        // Catch any unlinked shops
        List<BranchSummaryResponse> unlinked = branchMap.get(0L);
        if (unlinked != null && !unlinked.isEmpty()) {
            responses.add(new BusinessResponse(0L, "Default Business", unlinked));
        }

        return responses;
    }

    @Transactional(readOnly = true)
    public List<BranchSummaryResponse> getAllBranches(Long ownerId) {
        List<Shop> shops = shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerId);
        List<BranchSummaryResponse> result = new ArrayList<>();
        for (Shop s : shops) {
            result.add(toBranchSummary(s));
        }
        return result;
    }

    @Transactional
    public BusinessResponse createBusiness(Long ownerId, CreateBusinessRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Owner not found: " + ownerId));

        Business business = new Business(owner, request.getName().trim());
        business = businessRepository.save(business);

        auditLogRepository.save(new AuditLog(
                "CREATE_BUSINESS",
                owner.getPhone() != null ? owner.getPhone() : owner.getEmail(),
                "BUSINESS",
                business.getId().toString(),
                "Created business: " + business.getName(),
                null
        ));

        return new BusinessResponse(business.getId(), business.getName(), new ArrayList<>());
    }

    @Transactional
    public BranchSummaryResponse createBranch(Long ownerId, CreateBranchRequest request) {
        String clientKey = (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank())
                ? request.getIdempotencyKey().trim()
                : null;
        String requestHash = clientKey != null ? computeRequestHash(request) : null;

        // 1. Fast-path check: in-memory idempotency cache
        if (clientKey != null) {
            String cacheKey = ownerId + ":" + clientKey;
            CachedBranchCreation cached = idempotencyCache.get(cacheKey);
            if (cached != null && cached.createdAt().plus(IDEMPOTENCY_TTL).isAfter(Instant.now())) {
                if (cached.requestHash() != null && !cached.requestHash().equals(requestHash)) {
                    throw new IllegalStateException("Idempotency key has already been used with different branch creation details.");
                }
                log.info("Returning in-memory idempotently cached branch response (ownerId={}, key={})", ownerId, clientKey);
                return cached.response();
            }

            // Distributed check: persistent record (dedicated table or legacy fallback)
            BranchSummaryResponse persisted = checkPersistedIdempotency(ownerId, clientKey, requestHash);
            if (persisted != null) {
                return persisted;
            }
        }

        // 2. Database-level pessimistic write lock on the owner row.
        // Serializes concurrent branch creations for this owner across ALL backend instances.
        User owner = userRepository.findByIdForUpdate(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Owner not found: " + ownerId));

        // Re-check after acquiring the lock to guard against races between instances
        if (clientKey != null) {
            String cacheKey = ownerId + ":" + clientKey;
            CachedBranchCreation cached = idempotencyCache.get(cacheKey);
            if (cached != null && cached.createdAt().plus(IDEMPOTENCY_TTL).isAfter(Instant.now())) {
                if (cached.requestHash() != null && !cached.requestHash().equals(requestHash)) {
                    throw new IllegalStateException("Idempotency key has already been used with different branch creation details.");
                }
                return cached.response();
            }

            BranchSummaryResponse persisted = checkPersistedIdempotency(ownerId, clientKey, requestHash);
            if (persisted != null) {
                return persisted;
            }
        }

        Business business = null;
        if (request.getBusinessId() != null) {
            business = businessRepository.findByIdAndOwnerId(request.getBusinessId(), ownerId)
                    .orElseThrow(() -> new IllegalArgumentException("Business not found for owner: " + request.getBusinessId()));
        } else {
            // Find or create primary business
            List<Business> existingBiz = businessRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerId);
            if (!existingBiz.isEmpty()) {
                business = existingBiz.get(0);
            } else {
                business = businessRepository.save(new Business(owner, request.getName().trim()));
            }
        }

        List<Shop> existingShops = shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerId);
        boolean isFirstShop = existingShops.isEmpty();

        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setBusiness(business);
        shop.setName(request.getName().trim());
        shop.setBranchName(request.getBranchName() != null ? request.getBranchName().trim() : request.getName().trim());
        shop.setCategory(request.getCategory() != null && !request.getCategory().isBlank() ? request.getCategory().trim() : "General");
        shop.setAddress(request.getAddress() != null ? request.getAddress().trim() : null);
        shop.setPhone(request.getPhone() != null ? request.getPhone().trim() : owner.getPhone());
        shop.setAvgServiceDurationMinutes(request.getAvgServiceDurationMinutes() != null ? request.getAvgServiceDurationMinutes() : 15);

        if (isFirstShop) {
            shop.setIsPrimary(true);
            shop.setRegistrationStatus("REGISTERED");
            shop.setRegistrationFeePaid(BigDecimal.ZERO);
        } else {
            shop.setIsPrimary(false);
            shop.setRegistrationStatus("PAYMENT_PENDING");
            shop.setRegistrationFeePaid(BigDecimal.ZERO);
        }

        shop = shopRepository.save(shop);

        // Pre-create active session and publicQueueId so QR codes work immediately
        String publicQueueId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        QueueSession session = new QueueSession(shop, LocalDate.now(), publicQueueId);
        session = sessionRepository.save(session);

        activeQueueManager.getOrCreateShopQueue(shop, session, null);

        auditLogRepository.save(new AuditLog(
                "CREATE_BRANCH",
                owner.getPhone() != null ? owner.getPhone() : owner.getEmail(),
                "SHOP",
                shop.getId().toString(),
                "Created branch: " + shop.getName() + " (primary=" + shop.isPrimary() + ", status=" + shop.getRegistrationStatus() + ")",
                null
        ));

        BranchSummaryResponse summary = toBranchSummary(shop);

        // Record idempotency token if explicitly provided by the client
        if (clientKey != null) {
            String cacheKey = ownerId + ":" + clientKey;
            idempotencyCache.put(cacheKey, new CachedBranchCreation(summary, requestHash, Instant.now()));
            if (idempotencyKeyRepository != null) {
                idempotencyKeyRepository.save(new com.queueless.entity.IdempotencyKey(
                        owner,
                        "BRANCH_CREATION",
                        clientKey,
                        requestHash,
                        shop.getId().toString(),
                        java.time.LocalDateTime.now().plusHours(24)
                ));
            } else {
                auditLogRepository.save(new AuditLog(
                        "IDEMPOTENCY_RECORD",
                        owner.getPhone() != null ? owner.getPhone() : owner.getEmail(),
                        "BRANCH_IDEMPOTENCY",
                        cacheKey,
                        shop.getId().toString() + ":" + requestHash,
                        null
                ));
            }
        }

        evictExpiredIdempotencyEntries();
        return summary;
    }

    private BranchSummaryResponse checkPersistedIdempotency(Long ownerId, String clientKey, String requestHash) {
        if (clientKey == null) return null;
        String cacheKey = ownerId + ":" + clientKey;

        // 1. Check dedicated idempotency_keys table
        if (idempotencyKeyRepository != null) {
            Optional<com.queueless.entity.IdempotencyKey> keyRecord = idempotencyKeyRepository
                    .findByOwnerIdAndScopeAndIdempotencyKey(ownerId, "BRANCH_CREATION", clientKey);
            if (keyRecord.isPresent()) {
                com.queueless.entity.IdempotencyKey rec = keyRecord.get();
                if (rec.getExpiresAt() != null && rec.getExpiresAt().isAfter(java.time.LocalDateTime.now())) {
                    if (!rec.getRequestHash().equals(requestHash)) {
                        throw new IllegalStateException("Idempotency key has already been used with different branch creation details.");
                    }
                    try {
                        Long shopId = Long.parseLong(rec.getResponsePayload());
                        Optional<Shop> existingShop = shopRepository.findByIdAndOwnerId(shopId, ownerId);
                        if (existingShop.isPresent()) {
                            BranchSummaryResponse summary = toBranchSummary(existingShop.get());
                            idempotencyCache.put(cacheKey, new CachedBranchCreation(summary, requestHash, Instant.now()));
                            log.info("Returning dedicated DB-backed idempotently cached branch response (ownerId={}, key={}, shopId={})", ownerId, clientKey, shopId);
                            return summary;
                        }
                    } catch (IllegalStateException ise) {
                        throw ise;
                    } catch (Exception ignored) {}
                } else {
                    // Key is expired: remove stale record to avoid unique constraint violations on re-use
                    try {
                        idempotencyKeyRepository.delete(rec);
                        idempotencyKeyRepository.flush();
                    } catch (Exception ignored) {}
                }
            }
        }

        // 2. Legacy fallback to audit_logs for existing records
        Optional<AuditLog> dbRecord = auditLogRepository.findFirstByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                "BRANCH_IDEMPOTENCY", cacheKey);
        if (dbRecord.isPresent() && dbRecord.get().getCreatedAt() != null
                && dbRecord.get().getCreatedAt().isAfter(java.time.LocalDateTime.now().minusHours(1))) {
            String details = dbRecord.get().getDetails();
            if (details != null) {
                String[] parts = details.split(":", 2);
                if (parts.length > 1 && !parts[1].equals(requestHash)) {
                    throw new IllegalStateException("Idempotency key has already been used with different branch creation details.");
                }
                try {
                    Long shopId = Long.parseLong(parts[0]);
                    Optional<Shop> existingShop = shopRepository.findByIdAndOwnerId(shopId, ownerId);
                    if (existingShop.isPresent()) {
                        BranchSummaryResponse summary = toBranchSummary(existingShop.get());
                        idempotencyCache.put(cacheKey, new CachedBranchCreation(summary, requestHash, Instant.now()));
                        log.info("Returning legacy audit-backed idempotently cached branch response (ownerId={}, key={}, shopId={})", ownerId, clientKey, shopId);
                        return summary;
                    }
                } catch (IllegalStateException ise) {
                    throw ise;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    public void clearInMemoryIdempotencyCache() {
        idempotencyCache.clear();
    }

    private void evictExpiredIdempotencyEntries() {
        if (idempotencyCache.size() > 200) {
            Instant cutoff = Instant.now().minus(IDEMPOTENCY_TTL);
            idempotencyCache.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
        }
    }

    @Transactional
    public PaymentRecord submitBranchRegistrationPayment(Long ownerId, Long branchId, BranchRegistrationPaymentRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Owner not found: " + ownerId));
        Shop shop = shopRepository.findByIdAndOwnerId(branchId, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Branch not found for owner: " + branchId));

        if (shop.isPrimary() || "REGISTERED".equalsIgnoreCase(shop.getRegistrationStatus())) {
            throw new IllegalStateException("Branch is already registered. The \u20B999 registration fee is never charged again.");
        }

        String utr = request.getPaymentReference().trim();
        if (paymentRecordRepository.existsByPaymentReferenceAndStatus(utr, "APPROVED")) {
            throw new IllegalArgumentException("This payment reference / UTR has already been approved for another payment.");
        }

        boolean alreadyPending = paymentRecordRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .anyMatch(p -> "BRANCH_REGISTRATION".equalsIgnoreCase(p.getPaymentType())
                        && "PENDING_APPROVAL".equalsIgnoreCase(p.getStatus())
                        && ((p.getShop() != null && p.getShop().getId().equals(branchId))
                            || (p.getBatchShopIds() != null && Arrays.asList(p.getBatchShopIds().split(",")).contains(branchId.toString()))));
        if (alreadyPending) {
            throw new IllegalStateException("A registration payment for this branch is already pending administrator verification.");
        }

        String upiId = (request.getPaymentUpiId() != null && !request.getPaymentUpiId().isBlank())
                ? request.getPaymentUpiId().trim()
                : subscriptionService.getConfiguredUpiId();

        PaymentRecord record = new PaymentRecord(
                "BRANCH_REGISTRATION",
                owner,
                shop,
                null,
                BRANCH_REGISTRATION_FEE,
                utr,
                upiId
        );
        record.setNotes("One-time physical branch registration fee for " + shop.getName());
        record = paymentRecordRepository.save(record);

        auditLogRepository.save(new AuditLog(
                "SUBMIT_BRANCH_PAYMENT",
                owner.getPhone() != null ? owner.getPhone() : owner.getEmail(),
                "PAYMENT",
                record.getId().toString(),
                "Submitted \u20B999 branch registration payment for branch " + shop.getId() + " with UTR " + utr,
                null
        ));

        return record;
    }

    @Transactional
    public PaymentRecord submitBulkBranchRegistrationPayment(Long ownerId, BulkBranchPaymentRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Owner not found: " + ownerId));

        if (request.getBranchIds() == null || request.getBranchIds().isEmpty()) {
            throw new IllegalArgumentException("At least one branch must be selected for registration.");
        }

        List<Shop> branches = new ArrayList<>();
        List<Long> validBranchIds = new ArrayList<>();

        for (Long branchId : request.getBranchIds()) {
            Shop shop = shopRepository.findByIdAndOwnerId(branchId, ownerId)
                    .orElseThrow(() -> new IllegalArgumentException("Branch ID " + branchId + " does not belong to owner."));

            if (shop.isPrimary() || "REGISTERED".equalsIgnoreCase(shop.getRegistrationStatus())) {
                throw new IllegalStateException("Branch '" + shop.getName() + "' (ID " + branchId + ") is already registered. The \u20B999 fee cannot be charged again.");
            }

            boolean alreadyPending = paymentRecordRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                    .anyMatch(p -> "BRANCH_REGISTRATION".equalsIgnoreCase(p.getPaymentType())
                            && "PENDING_APPROVAL".equalsIgnoreCase(p.getStatus())
                            && ((p.getShop() != null && p.getShop().getId().equals(branchId))
                                || (p.getBatchShopIds() != null && Arrays.asList(p.getBatchShopIds().split(",")).contains(branchId.toString()))));
            if (alreadyPending) {
                throw new IllegalStateException("A registration payment for branch '" + shop.getName() + "' (ID " + branchId + ") is already pending administrator verification.");
            }

            branches.add(shop);
            validBranchIds.add(branchId);
        }

        String utr = request.getPaymentReference().trim();
        if (paymentRecordRepository.existsByPaymentReferenceAndStatus(utr, "APPROVED")) {
            throw new IllegalArgumentException("This payment reference / UTR has already been approved for another payment.");
        }

        BigDecimal totalAmount = BRANCH_REGISTRATION_FEE.multiply(BigDecimal.valueOf(branches.size()));
        String upiId = (request.getPaymentUpiId() != null && !request.getPaymentUpiId().isBlank())
                ? request.getPaymentUpiId().trim()
                : subscriptionService.getConfiguredUpiId();

        String batchIdsStr = validBranchIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));

        PaymentRecord record = new PaymentRecord(
                "BRANCH_REGISTRATION",
                owner,
                branches.get(0),
                null,
                totalAmount,
                utr,
                upiId
        );
        record.setBatchCount(branches.size());
        record.setBatchShopIds(batchIdsStr);
        record.setNotes("Bulk one-time physical branch registration fee for " + branches.size() + " branches (" + batchIdsStr + ")");
        record = paymentRecordRepository.save(record);

        auditLogRepository.save(new AuditLog(
                "SUBMIT_BULK_BRANCH_PAYMENT",
                owner.getPhone() != null ? owner.getPhone() : owner.getEmail(),
                "PAYMENT",
                record.getId().toString(),
                "Submitted bulk \u20B9" + totalAmount + " payment for " + branches.size() + " branches with UTR " + utr,
                null
        ));

        return record;
    }

    private BranchSummaryResponse toBranchSummary(Shop s) {
        BranchSummaryResponse res = new BranchSummaryResponse();
        res.setId(s.getId());
        if (s.getBusiness() != null) {
            res.setBusinessId(s.getBusiness().getId());
            res.setBusinessName(s.getBusiness().getName());
        }
        res.setName(s.getName());
        res.setBranchName(s.getBranchName() != null ? s.getBranchName() : s.getName());
        res.setCategory(s.getCategory());
        res.setAddress(s.getAddress());
        res.setPhone(s.getPhone());
        res.setAvgServiceDurationMinutes(s.getAvgServiceDurationMinutes());
        res.setIsPrimary(s.isPrimary());
        res.setRegistrationStatus(s.getRegistrationStatus());
        res.setRegistrationFeePaid(s.getRegistrationFeePaid());

        var q = activeQueueManager.getShopQueue(s.getId());
        if (q != null) {
            res.setPublicQueueId(q.getPublicQueueId());
            res.setQueueOpen(q.isOpen());
        } else {
            sessionRepository.findTopByShopIdAndSessionDateOrderByStartedAtDesc(s.getId(), LocalDate.now())
                    .ifPresent(session -> {
                        res.setPublicQueueId(session.getPublicQueueId());
                        res.setQueueOpen("OPEN".equalsIgnoreCase(session.getStatus().name()));
                    });
        }
        return res;
    }
}
