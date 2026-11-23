package com.queueless.controller;

import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.repository.AuditLogRepository;
import com.queueless.repository.BusinessCategoryChangeRequestRepository;
import com.queueless.repository.BusinessRepository;
import com.queueless.repository.PaymentRecordRepository;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.SubscriptionRepository;
import com.queueless.repository.SubscriptionSeatRepository;
import com.queueless.repository.UserRepository;
import com.queueless.service.CategoryChangeService;
import com.queueless.service.SubscriptionService;
import com.queueless.service.BranchService;
import com.queueless.service.SeatService;
import com.queueless.service.SseEmitterService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final SubscriptionService subscriptionService;
    private final CategoryChangeService categoryChangeService;
    private final com.queueless.service.AuthService authService;
    private final PaymentRecordRepository paymentRecordRepository;
    private final ShopRepository shopRepository;
    private final SubscriptionSeatRepository seatRepository;
    private final AuditLogRepository auditLogRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final BusinessCategoryChangeRequestRepository categoryChangeRequestRepository;
    private final com.queueless.repository.QueueSessionRepository queueSessionRepository;
    private final com.queueless.service.DeviceAuthService deviceAuthService;
    private final SseEmitterService sseEmitterService;
    private final Clock clock;
    private final String adminKey;

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 100;

    private int clampPage(Integer page) {
        return (page != null && page >= 0) ? page : 0;
    }

    private int clampSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    public AdminController(SubscriptionService subscriptionService,
                           CategoryChangeService categoryChangeService,
                           com.queueless.service.AuthService authService,
                           PaymentRecordRepository paymentRecordRepository,
                           ShopRepository shopRepository,
                           SubscriptionSeatRepository seatRepository,
                           AuditLogRepository auditLogRepository,
                           SubscriptionRepository subscriptionRepository,
                           UserRepository userRepository,
                           BusinessRepository businessRepository,
                           BusinessCategoryChangeRequestRepository categoryChangeRequestRepository,
                           com.queueless.repository.QueueSessionRepository queueSessionRepository,
                           com.queueless.service.DeviceAuthService deviceAuthService,
                           SseEmitterService sseEmitterService,
                           Clock clock,
                           @Value("${app.admin.key:admin-secret-queueless-2026}") String adminKey) {
        this.subscriptionService = subscriptionService;
        this.categoryChangeService = categoryChangeService;
        this.authService = authService;
        this.paymentRecordRepository = paymentRecordRepository;
        this.shopRepository = shopRepository;
        this.seatRepository = seatRepository;
        this.auditLogRepository = auditLogRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.categoryChangeRequestRepository = categoryChangeRequestRepository;
        this.queueSessionRepository = queueSessionRepository;
        this.deviceAuthService = deviceAuthService;
        this.sseEmitterService = sseEmitterService;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        this.adminKey = adminKey;
    }

    private void verifySuperAdminAccess() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();

        boolean hasSuperAdminRole = auth != null && auth.isAuthenticated() &&
                auth.getAuthorities().stream().anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));

        if (!hasSuperAdminRole) {
            log.warn("AUDIT: Unauthorized super admin access attempt detected - principal: {}, authorities: {}, timestamp: {}",
                    auth != null ? auth.getName() : "anonymous",
                    auth != null ? auth.getAuthorities() : "none",
                    Instant.now());
            throw new org.springframework.security.access.AccessDeniedException("Access restricted: SUPER_ADMIN role required");
        }
    }

    private void verifyOperationalAdminAccess(String headerKey) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();

        boolean hasPrivilegedRole = auth != null && auth.isAuthenticated() &&
                auth.getAuthorities().stream().anyMatch(a ->
                        "ROLE_SUPER_ADMIN".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority())
                );

        boolean hasValidKey = headerKey != null && !headerKey.isBlank() &&
                MessageDigest.isEqual(headerKey.getBytes(StandardCharsets.UTF_8), adminKey.getBytes(StandardCharsets.UTF_8));

        if (!hasPrivilegedRole && !hasValidKey) {
            log.warn("AUDIT: Unauthorized operational admin access attempt detected - principal: {}, keyPresent: {}, timestamp: {}",
                    auth != null ? auth.getName() : "anonymous",
                    (headerKey != null && !headerKey.isBlank()), Instant.now());
            throw new org.springframework.security.access.AccessDeniedException("Access restricted: Administrator privileges required");
        }
    }

    private String resolveActor(String headerKey) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        boolean hasValidKey = headerKey != null && !headerKey.isBlank() &&
                MessageDigest.isEqual(headerKey.getBytes(StandardCharsets.UTF_8), adminKey.getBytes(StandardCharsets.UTF_8));
        if (hasValidKey) {
            return "service:automation";
        }
        return "anonymous";
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return "null";
        String clean = phone.trim();
        if (clean.length() <= 4) {
            return "****";
        }
        return clean.substring(0, clean.length() - 4).replaceAll(".", "*") + clean.substring(clean.length() - 4);
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        verifyOperationalAdminAccess(key);
        return ResponseEntity.ok(Map.of(
                "upiId", subscriptionService.getConfiguredUpiId(),
                "upiName", subscriptionService.getConfiguredUpiName(),
                "monthlyPrice", subscriptionService.getConfiguredMonthlyPrice(),
                "yearlyPrice", subscriptionService.getConfiguredYearlyPrice()
        ));
    }

    @PostMapping("/settings")
    public ResponseEntity<Map<String, String>> updateSettings(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody AdminSettingRequest request,
            HttpServletRequest httpRequest) {
        verifyOperationalAdminAccess(key);
        subscriptionService.adminUpdateSettings(request);
        String actor = resolveActor(key);
        String clientIp = httpRequest != null ? httpRequest.getRemoteAddr() : "127.0.0.1";
        auditLogRepository.save(new AuditLog(
                "ADMIN_SETTINGS_UPDATE",
                actor,
                "SYSTEM",
                "SETTINGS",
                "Updated UPI ID and subscription pricing configuration",
                clientIp
        ));
        return ResponseEntity.ok(Map.of("message", "Subscription settings updated successfully."));
    }

    @GetMapping("/shops")
    public ResponseEntity<List<AdminShopItem>> listShops(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        verifyOperationalAdminAccess(key);
        List<AdminShopItem> allShops = subscriptionService.adminGetAllShops();
        long totalCount = allShops.size();
        if (page == null && size == null) {
            return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(totalCount))
                    .body(allShops);
        }
        int p = clampPage(page);
        int s = clampSize(size);
        int fromIndex = Math.min(p * s, allShops.size());
        int toIndex = Math.min(fromIndex + s, allShops.size());
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(totalCount))
                .body(allShops.subList(fromIndex, toIndex));
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, String>> activateShopSubscription(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody AdminShopActivateRequest request,
            HttpServletRequest httpRequest) {
        verifySuperAdminAccess();
        subscriptionService.adminActivateShop(request);
        String actor = resolveActor(key);
        String clientIp = httpRequest != null ? httpRequest.getRemoteAddr() : "127.0.0.1";
        auditLogRepository.save(new AuditLog(
                "SUBSCRIPTION_MANUAL_ACTIVATION",
                actor,
                "SHOP",
                request.getShopId() != null ? request.getShopId().toString() : "unknown",
                "Plan: " + request.getPlanType() + ", CustomDays: " + request.getCustomDays(),
                clientIp
        ));
        return ResponseEntity.ok(Map.of("message", "Shop subscription activated/extended successfully."));
    }

    @GetMapping("/category-requests")
    public ResponseEntity<List<AdminCategoryChangeItem>> listCategoryChangeRequests(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        verifyOperationalAdminAccess(key);
        List<AdminCategoryChangeItem> allRequests = categoryChangeService.adminListRequests();
        long totalCount = allRequests.size();
        if (page == null && size == null) {
            return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(totalCount))
                    .body(allRequests);
        }
        int p = clampPage(page);
        int s = clampSize(size);
        int fromIndex = Math.min(p * s, allRequests.size());
        int toIndex = Math.min(fromIndex + s, allRequests.size());
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(totalCount))
                .body(allRequests.subList(fromIndex, toIndex));
    }

    @PostMapping("/category-requests/{id}/approve")
    public ResponseEntity<Map<String, String>> approveCategoryChangeRequest(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable("id") Long id) {
        verifyOperationalAdminAccess(key);
        categoryChangeService.adminApproveRequest(id, "admin");
        return ResponseEntity.ok(Map.of("message", "Category change request approved successfully."));
    }

    @PostMapping("/category-requests/{id}/reject")
    public ResponseEntity<Map<String, String>> rejectCategoryChangeRequest(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable("id") Long id,
            @RequestBody(required = false) AdminCategoryRejectRequest request) {
        verifyOperationalAdminAccess(key);
        categoryChangeService.adminRejectRequest(id, request, "admin");
        return ResponseEntity.ok(Map.of("message", "Category change request rejected."));
    }

    private List<AdminPaymentItem> mapPaymentRecords(List<PaymentRecord> records) {
        List<AdminPaymentItem> items = new ArrayList<>();
        if (records == null) return items;
        for (PaymentRecord r : records) {
            AdminPaymentItem item = new AdminPaymentItem();
            item.setId(r.getId());
            item.setPaymentType(r.getPaymentType());
            if (r.getOwner() != null) {
                item.setOwnerId(r.getOwner().getId());
                item.setOwnerName(r.getOwner().getFullName());
                item.setOwnerPhone(r.getOwner().getPhone());
            }
            if (r.getShop() != null) {
                item.setShopId(r.getShop().getId());
                item.setShopName(r.getShop().getName());
            }
            if (r.getSeat() != null) {
                item.setSeatId(r.getSeat().getId());
            }
            item.setAmount(r.getAmount());
            item.setPaymentReference(r.getPaymentReference());
            item.setPaymentUpiId(r.getPaymentUpiId());
            item.setStatus(r.getStatus());
            item.setPlanType(r.getPlanType());
            item.setBatchShopIds(r.getBatchShopIds());
            item.setBatchCount(r.getBatchCount());
            item.setNotes(r.getNotes());
            item.setRejectionReason(r.getRejectionReason());
            item.setCreatedAt(r.getCreatedAt());
            item.setReviewedAt(r.getReviewedAt());
            item.setReviewedBy(r.getReviewedBy());
            items.add(item);
        }
        return items;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/dashboard/summary")
    public ResponseEntity<AdminDashboardSummaryResponse> getDashboardSummary(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        verifySuperAdminAccess();

        AdminDashboardSummaryResponse resp = new AdminDashboardSummaryResponse();
        resp.setTotalShopOwners(userRepository.countByRole(UserRole.ROLE_OWNER));
        resp.setTotalRegisteredBranches(shopRepository.countByRegistrationStatus("REGISTERED"));
        resp.setActiveSubscriptions(subscriptionRepository.countByStatus(SubscriptionStatus.ACTIVE));
        resp.setExpiredSubscriptions(subscriptionRepository.countByStatus(SubscriptionStatus.EXPIRED));
        resp.setPendingPayments(paymentRecordRepository.countByStatus("PENDING_APPROVAL"));
        resp.setTotalApprovedPayments(paymentRecordRepository.countByStatus("APPROVED"));
        java.math.BigDecimal approvedTotal = paymentRecordRepository.sumAmountByStatus("APPROVED");
        resp.setTotalApprovedAmount(approvedTotal != null ? approvedTotal : java.math.BigDecimal.ZERO);

        // Latest pending payments (top 5)
        List<PaymentRecord> pendingRecords = paymentRecordRepository.findTop5ByStatusOrderByCreatedAtDesc("PENDING_APPROVAL");
        resp.setLatestPendingPayments(mapPaymentRecords(pendingRecords));

        // Recently approved payments (top 5)
        List<PaymentRecord> approvedRecords = paymentRecordRepository.findTop5ByStatusOrderByReviewedAtDesc("APPROVED");
        resp.setRecentlyApprovedPayments(mapPaymentRecords(approvedRecords));

        // Recently registered shop owners (top 5)
        List<User> recentOwners = userRepository.findTop5ByRoleOrderByCreatedAtDesc(UserRole.ROLE_OWNER);
        List<AdminDashboardSummaryResponse.RecentOwnerItem> ownerItems = new ArrayList<>();
        for (User u : recentOwners) {
            int branchCount = shopRepository.findAllByOwnerIdOrderByCreatedAtAsc(u.getId()).size();
            Subscription latestSub = subscriptionRepository.findTopByOwnerIdOrderByEndDateDesc(u.getId()).orElse(null);
            String subStatus = latestSub != null ? latestSub.getStatus().name() : "NONE";
            ownerItems.add(new AdminDashboardSummaryResponse.RecentOwnerItem(
                    u.getId(),
                    u.getFullName(),
                    u.getPhone(),
                    u.getEmail(),
                    u.getCreatedAt(),
                    branchCount,
                    subStatus
            ));
        }
        resp.setRecentlyRegisteredOwners(ownerItems);

        return ResponseEntity.ok(resp);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/payments")
    public ResponseEntity<List<AdminPaymentItem>> listPayments(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        verifySuperAdminAccess();

        List<PaymentRecord> records;
        long totalCount;
        if (page != null || size != null) {
            int safePage = clampPage(page);
            int safeSize = clampSize(size);
            org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(safePage, safeSize);
            org.springframework.data.domain.Page<PaymentRecord> pageResult = (status != null && !status.isBlank())
                    ? paymentRecordRepository.findAllByStatusOrderByCreatedAtDesc(status.toUpperCase().trim(), pageable)
                    : paymentRecordRepository.findAllByOrderByCreatedAtDesc(pageable);
            records = pageResult.getContent();
            totalCount = pageResult.getTotalElements();
        } else {
            records = (status != null && !status.isBlank())
                    ? paymentRecordRepository.findAllByStatusOrderByCreatedAtDesc(status.toUpperCase().trim())
                    : paymentRecordRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
            totalCount = records.size();
        }

        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(totalCount))
                .body(mapPaymentRecords(records));
    }

    private BigDecimal calculateExpectedPaymentAmount(PaymentRecord record) {
        String type = record.getPaymentType() != null ? record.getPaymentType().toUpperCase().trim() : "";
        switch (type) {
            case "SUBSCRIPTION":
                if ("YEARLY".equalsIgnoreCase(record.getPlanType())) {
                    return subscriptionService.getConfiguredYearlyPrice();
                } else if ("MONTHLY".equalsIgnoreCase(record.getPlanType())) {
                    return subscriptionService.getConfiguredMonthlyPrice();
                } else {
                    BigDecimal yearlyPrice = subscriptionService.getConfiguredYearlyPrice();
                    BigDecimal monthlyPrice = subscriptionService.getConfiguredMonthlyPrice();
                    if (record.getAmount() != null && record.getAmount().compareTo(yearlyPrice) == 0) {
                        return yearlyPrice;
                    }
                    return monthlyPrice;
                }
            case "BRANCH_REGISTRATION":
                int count = 1;
                if (record.getBatchShopIds() != null && !record.getBatchShopIds().isBlank()) {
                    count = record.getBatchShopIds().split(",").length;
                } else if (record.getBatchCount() != null && record.getBatchCount() > 1) {
                    count = record.getBatchCount();
                }
                return BranchService.BRANCH_REGISTRATION_FEE.multiply(BigDecimal.valueOf(count));
            case "USER_SEAT":
                if ("YEARLY".equalsIgnoreCase(record.getPlanType())) {
                    return SeatService.SEAT_YEARLY_PRICE;
                } else if ("MONTHLY".equalsIgnoreCase(record.getPlanType())) {
                    return SeatService.SEAT_MONTHLY_PRICE;
                } else {
                    if (record.getAmount() != null && record.getAmount().compareTo(SeatService.SEAT_YEARLY_PRICE) == 0) {
                        return SeatService.SEAT_YEARLY_PRICE;
                    }
                    return SeatService.SEAT_MONTHLY_PRICE;
                }
            case "CATEGORY_CHANGE":
                return BusinessCategoryConstants.CATEGORY_CHANGE_FEE;
            default:
                throw new IllegalStateException("Unsupported payment purpose: " + record.getPaymentType());
        }
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    @PostMapping("/payments/{id}/approve")
    public ResponseEntity<Map<String, String>> approvePayment(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable("id") Long id,
            HttpServletRequest httpRequest) {
        verifySuperAdminAccess();
        PaymentRecord record = paymentRecordRepository.findByIdForUpdate(id)
                .orElseGet(() -> paymentRecordRepository.findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("Payment record not found: " + id)));

        // 1. Idempotency check: duplicate approval must never extend or activate entitlement twice
        if ("APPROVED".equalsIgnoreCase(record.getStatus())) {
            return ResponseEntity.ok(Map.of("message", "Payment has already been approved."));
        }

        // 2. Status verification: record must still be pending approval
        if (!"PENDING_APPROVAL".equalsIgnoreCase(record.getStatus())) {
            throw new IllegalStateException("Payment record is not pending approval (currently " + record.getStatus() + ").");
        }

        // 3. Owner verification: record must belong to a valid registered owner
        User owner = record.getOwner();
        if (owner == null) {
            throw new IllegalStateException("Payment record does not belong to a valid shop owner.");
        }

        // 4. Purpose and expected amount verification
        BigDecimal expectedAmount = calculateExpectedPaymentAmount(record);
        if (record.getAmount() == null || record.getAmount().compareTo(expectedAmount) != 0) {
            throw new IllegalStateException("Payment amount mismatch: Expected \u20B9" + expectedAmount + " for " + record.getPaymentType() + ", but found \u20B9" + record.getAmount());
        }

        // 5. UTR verification & duplicate payment policy check
        String utr = record.getPaymentReference() != null ? record.getPaymentReference().trim() : "";
        if (utr.isBlank() || utr.length() < 6) {
            throw new IllegalStateException("Invalid or missing payment reference / UTR.");
        }
        if (paymentRecordRepository.existsByPaymentReferenceAndStatus(utr, "APPROVED")) {
            throw new IllegalStateException("Duplicate approval error: This UTR (" + utr + ") has already been approved for another payment record.");
        }

        String actor = resolveActor(key);
        String clientIp = httpRequest != null ? httpRequest.getRemoteAddr() : "127.0.0.1";
        LocalDateTime now = LocalDateTime.now(clock);

        record.setStatus("APPROVED");
        record.setReviewedAt(now);
        record.setReviewedBy(actor);
        record.setNotes("Manually verified and approved by administrator (" + actor + ")");

        // 6. Transactional activation of only the relevant entitlement
        if ("BRANCH_REGISTRATION".equalsIgnoreCase(record.getPaymentType())) {
            if (record.getBatchShopIds() != null && !record.getBatchShopIds().isBlank()) {
                String[] shopIds = record.getBatchShopIds().split(",");
                for (String sid : shopIds) {
                    try {
                        Long branchId = Long.parseLong(sid.trim());
                        Shop s = shopRepository.findById(branchId)
                                .orElseThrow(() -> new IllegalStateException("Branch not found: " + branchId));
                        if (!s.getOwner().getId().equals(owner.getId())) {
                            throw new IllegalStateException("Branch #" + branchId + " does not belong to the payment owner.");
                        }
                        if (!"REGISTERED".equalsIgnoreCase(s.getRegistrationStatus())) {
                            s.setRegistrationStatus("REGISTERED");
                            s.setRegistrationFeePaid(BranchService.BRANCH_REGISTRATION_FEE);
                            shopRepository.save(s);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            } else {
                Shop shop = record.getShop();
                if (shop == null) {
                    throw new IllegalStateException("Branch registration payment has no associated branch.");
                }
                if (!shop.getOwner().getId().equals(owner.getId())) {
                    throw new IllegalStateException("Branch #" + shop.getId() + " does not belong to the payment owner.");
                }
                if (!"REGISTERED".equalsIgnoreCase(shop.getRegistrationStatus())) {
                    shop.setRegistrationStatus("REGISTERED");
                    shop.setRegistrationFeePaid(record.getAmount());
                    shopRepository.save(shop);
                }
            }
        } else if ("USER_SEAT".equalsIgnoreCase(record.getPaymentType())) {
            SubscriptionPlan plan = "YEARLY".equalsIgnoreCase(record.getPlanType())
                    ? SubscriptionPlan.YEARLY : SubscriptionPlan.MONTHLY;
            int days = plan == SubscriptionPlan.YEARLY ? 365 : 30;
            LocalDate start = LocalDate.now(clock);
            LocalDate end = start.plusDays(days);

            SubscriptionSeat seat = new SubscriptionSeat(
                    owner,
                    plan,
                    SubscriptionStatus.ACTIVE,
                    start,
                    end,
                    record.getAmount()
            );
            seat.setPaymentReference(record.getPaymentReference());
            seat = seatRepository.save(seat);
            record.setSeat(seat);
        } else if ("SUBSCRIPTION".equalsIgnoreCase(record.getPaymentType())) {
            Shop targetShop = record.getShop();
            if (targetShop == null) {
                targetShop = shopRepository.findFirstByOwnerIdOrderByIsPrimaryDescCreatedAtAsc(owner.getId()).orElse(null);
            }
            if (targetShop == null) {
                throw new IllegalStateException("No registered shop found for owner subscription.");
            }
            if (!targetShop.getOwner().getId().equals(owner.getId())) {
                throw new IllegalStateException("Shop #" + targetShop.getId() + " does not belong to payment owner.");
            }

            int daysToAdd = "YEARLY".equalsIgnoreCase(record.getPlanType()) ? 365 : 30;
            SubscriptionPlan plan = "YEARLY".equalsIgnoreCase(record.getPlanType())
                    ? SubscriptionPlan.YEARLY : SubscriptionPlan.MONTHLY;

            Subscription latestActive = subscriptionRepository.findTopByShopIdOrderByEndDateDesc(targetShop.getId()).orElse(null);
            LocalDate today = LocalDate.now(clock);
            LocalDate baseDate = today;

            if (latestActive != null && latestActive.getEndDate() != null && !latestActive.getEndDate().isBefore(today)
                    && latestActive.getStatus() == SubscriptionStatus.ACTIVE) {
                baseDate = latestActive.getEndDate();
            }
            LocalDate newEndDate = baseDate.plusDays(daysToAdd);

            Subscription pendingSub = subscriptionRepository.findByShopIdOrderByCreatedAtDesc(targetShop.getId()).stream()
                    .filter(s -> s.getStatus() == SubscriptionStatus.PENDING_APPROVAL)
                    .findFirst()
                    .orElse(null);

            if (pendingSub != null) {
                pendingSub.setStatus(SubscriptionStatus.ACTIVE);
                pendingSub.setPlanType(plan);
                pendingSub.setStartDate(today);
                pendingSub.setEndDate(newEndDate);
                pendingSub.setAmount(record.getAmount());
                pendingSub.setPaymentReference(record.getPaymentReference());
                pendingSub.setNotes("Manually verified and approved by administrator (" + actor + ") for " + daysToAdd + " days");
                subscriptionRepository.save(pendingSub);
            } else {
                Subscription activeSub = new Subscription(
                        targetShop,
                        plan,
                        SubscriptionStatus.ACTIVE,
                        today,
                        newEndDate,
                        record.getAmount()
                );
                activeSub.setPaymentReference(record.getPaymentReference());
                activeSub.setNotes("Manually verified and approved by administrator (" + actor + ") for " + daysToAdd + " days");
                subscriptionRepository.save(activeSub);
            }
        } else if ("CATEGORY_CHANGE".equalsIgnoreCase(record.getPaymentType())) {
            categoryChangeRequestRepository.findByPaymentReference(record.getPaymentReference()).ifPresent(req -> {
                if (req.getStatus() != CategoryChangeStatus.APPROVED) {
                    categoryChangeService.adminApproveRequest(req.getId(), actor);
                }
            });
        }

        paymentRecordRepository.save(record);

        // 7. Audit Logging
        auditLogRepository.save(new AuditLog(
                "APPROVE_PAYMENT",
                actor,
                "PAYMENT",
                record.getId().toString(),
                "Manually verified and approved " + record.getPaymentType() + " payment (\u20B9" + record.getAmount() + ") with UTR " + record.getPaymentReference() + " for owner " + owner.getFullName(),
                clientIp
        ));

        // 8. Live Notification to Owner
        Long notifyShopId = record.getShop() != null ? record.getShop().getId() :
                shopRepository.findFirstByOwnerIdOrderByIsPrimaryDescCreatedAtAsc(owner.getId())
                        .map(Shop::getId).orElse(null);
        if (notifyShopId != null && sseEmitterService != null) {
            sseEmitterService.notifyOwner(notifyShopId, "PAYMENT_VERIFICATION_RESULT", Map.of(
                    "paymentId", record.getId(),
                    "status", "APPROVED",
                    "paymentType", record.getPaymentType(),
                    "amount", record.getAmount(),
                    "reference", record.getPaymentReference()
            ));
        }

        return ResponseEntity.ok(Map.of("message", "Payment approved successfully."));
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    @PostMapping("/payments/{id}/reject")
    public ResponseEntity<Map<String, String>> rejectPayment(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable("id") Long id,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest httpRequest) {
        verifySuperAdminAccess();
        PaymentRecord record = paymentRecordRepository.findByIdForUpdate(id)
                .orElseGet(() -> paymentRecordRepository.findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("Payment record not found: " + id)));

        if ("REJECTED".equalsIgnoreCase(record.getStatus())) {
            return ResponseEntity.ok(Map.of("message", "Payment has already been rejected."));
        }

        String reason = (body != null && body.get("reason") != null) ? body.get("reason").trim() : "";
        if (reason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason is required.");
        }

        String actor = resolveActor(key);
        String clientIp = httpRequest != null ? httpRequest.getRemoteAddr() : "127.0.0.1";
        LocalDateTime now = LocalDateTime.now(clock);

        record.setStatus("REJECTED");
        record.setRejectionReason(reason);
        record.setReviewedAt(now);
        record.setReviewedBy(actor);
        paymentRecordRepository.save(record);

        // Cancel / expire pending subscription row so owner can submit corrected payment
        if ("SUBSCRIPTION".equalsIgnoreCase(record.getPaymentType())) {
            Shop targetShop = record.getShop();
            if (targetShop == null && record.getOwner() != null) {
                targetShop = shopRepository.findFirstByOwnerIdOrderByIsPrimaryDescCreatedAtAsc(record.getOwner().getId()).orElse(null);
            }
            if (targetShop != null) {
                subscriptionRepository.findByShopIdOrderByCreatedAtDesc(targetShop.getId()).stream()
                        .filter(s -> s.getStatus() == SubscriptionStatus.PENDING_APPROVAL)
                        .forEach(s -> {
                            s.setStatus(SubscriptionStatus.EXPIRED);
                            s.setNotes("Payment verification rejected by administrator (" + actor + "): " + reason);
                            subscriptionRepository.save(s);
                        });
            }
        } else if ("CATEGORY_CHANGE".equalsIgnoreCase(record.getPaymentType())) {
            categoryChangeRequestRepository.findByPaymentReference(record.getPaymentReference()).ifPresent(req -> {
                if (req.getStatus() != CategoryChangeStatus.REJECTED) {
                    AdminCategoryRejectRequest rejectReq = new AdminCategoryRejectRequest();
                    rejectReq.setReason(reason);
                    categoryChangeService.adminRejectRequest(req.getId(), rejectReq, actor);
                }
            });
        }

        auditLogRepository.save(new AuditLog(
                "REJECT_PAYMENT",
                actor,
                "PAYMENT",
                record.getId().toString(),
                "Rejected " + record.getPaymentType() + " payment with UTR " + record.getPaymentReference() + ": " + reason,
                clientIp
        ));

        User owner = record.getOwner();
        Long notifyShopId = record.getShop() != null ? record.getShop().getId() :
                (owner != null ? shopRepository.findFirstByOwnerIdOrderByIsPrimaryDescCreatedAtAsc(owner.getId())
                        .map(Shop::getId).orElse(null) : null);
        if (notifyShopId != null && sseEmitterService != null) {
            sseEmitterService.notifyOwner(notifyShopId, "PAYMENT_VERIFICATION_RESULT", Map.of(
                    "paymentId", record.getId(),
                    "status", "REJECTED",
                    "paymentType", record.getPaymentType(),
                    "reason", reason,
                    "reference", record.getPaymentReference()
            ));
        }

        return ResponseEntity.ok(Map.of("message", "Payment rejected."));
    }

    @PostMapping("/reset-owner-pin")
    public ResponseEntity<Map<String, String>> adminResetPin(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody Map<String, String> body) {
        verifyOperationalAdminAccess(key);
        String phone = body != null ? body.get("phone") : null;
        String newPin = body != null ? body.get("newPin") : null;
        String actor = resolveActor(key);
        try {
            Long targetOwnerId = authService.adminResetPin(phone, newPin);
            log.info("AUDIT: Admin PIN reset SUCCESS - adminIdentity: {}, targetOwnerId: {}, targetPhone: {}, timestamp: {}",
                    actor, targetOwnerId, maskPhone(phone), Instant.now());
            return ResponseEntity.ok(Map.of("message", "Owner PIN successfully reset by administrator."));
        } catch (Exception e) {
            log.warn("AUDIT: Admin PIN reset FAILED - adminIdentity: {}, targetPhone: {}, reason: {}, timestamp: {}",
                    actor, maskPhone(phone), e.getMessage(), Instant.now());
            throw e;
        }
    }

    @GetMapping("/device-replacements")
    public ResponseEntity<List<DeviceReplacementResponse>> listPendingDeviceReplacements(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        verifySuperAdminAccess();
        List<DeviceReplacementResponse> allReplacements = deviceAuthService.getAllRequestsForAdmin();
        long totalCount = allReplacements.size();
        if (page == null && size == null) {
            return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(totalCount))
                    .body(allReplacements);
        }
        int p = clampPage(page);
        int s = clampSize(size);
        int fromIndex = Math.min(p * s, allReplacements.size());
        int toIndex = Math.min(fromIndex + s, allReplacements.size());
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(totalCount))
                .body(allReplacements.subList(fromIndex, toIndex));
    }

    @PostMapping("/device-replacements/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveDeviceReplacement(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable("id") Long id) {
        verifySuperAdminAccess();
        User adminUser = new User();
        adminUser.setId(0L);
        adminUser.setEmail(resolveActor(key));
        adminUser.setRole(UserRole.ROLE_SUPER_ADMIN);
        DeviceReplacementResponse resp = deviceAuthService.approveDeviceReplacement(id, adminUser, "Approved by administrator");
        return ResponseEntity.ok(Map.of(
                "message", "Device replacement request approved by administrator. Old device revoked; new device active.",
                "request", resp
        ));
    }

    @PostMapping("/device-replacements/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectDeviceReplacement(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable("id") Long id,
            @RequestBody(required = false) Map<String, String> body) {
        verifySuperAdminAccess();
        String reason = body != null && body.get("reason") != null ? body.get("reason").trim() : "Rejected by administrator";
        User adminUser = new User();
        adminUser.setId(0L);
        adminUser.setEmail(resolveActor(key));
        adminUser.setRole(UserRole.ROLE_SUPER_ADMIN);
        DeviceReplacementResponse resp = deviceAuthService.rejectDeviceReplacement(id, adminUser, reason);
        return ResponseEntity.ok(Map.of(
                "message", "Device replacement request rejected.",
                "request", resp
        ));
    }

    @PostMapping("/users/{id}/suspend")
    public ResponseEntity<Map<String, String>> suspendUser(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable("id") Long id,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        verifySuperAdminAccess();
        String reason = body != null && body.get("reason") != null && !body.get("reason").isBlank()
                ? body.get("reason").trim()
                : "Suspended by Super Admin";

        User target = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + id));

        if (target.getRole() == UserRole.ROLE_SUPER_ADMIN) {
            throw new IllegalStateException("Cannot suspend Super Admin accounts.");
        }

        target.setStatus("REVOKED");
        target.setActiveSessionId(null);
        target.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(target);

        String actor = resolveActor(key);
        String clientIp = request != null ? request.getRemoteAddr() : "127.0.0.1";
        auditLogRepository.save(new AuditLog(
                "ADMIN_ACCOUNT_SUSPENSION",
                actor,
                "USER",
                id.toString(),
                "Account suspended by Super Admin: " + reason,
                clientIp
        ));

        log.info("AUDIT: User account suspended by Super Admin (actor={}, targetId={}, reason={})",
                actor, id, reason);
        return ResponseEntity.ok(Map.of("message", "User account suspended successfully."));
    }

    @PostMapping("/users/{id}/reactivate")
    public ResponseEntity<Map<String, String>> reactivateUser(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable("id") Long id,
            HttpServletRequest request) {
        verifySuperAdminAccess();

        User target = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + id));

        target.setStatus("ACTIVE");
        userRepository.save(target);

        String actor = resolveActor(key);
        String clientIp = request != null ? request.getRemoteAddr() : "127.0.0.1";
        auditLogRepository.save(new AuditLog(
                "ADMIN_ACCOUNT_REACTIVATION",
                actor,
                "USER",
                id.toString(),
                "Account reactivated by Super Admin",
                clientIp
        ));

        log.info("AUDIT: User account reactivated by Super Admin (actor={}, targetId={})",
                actor, id);
        return ResponseEntity.ok(Map.of("message", "User account reactivated successfully."));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/subscriptions")
    public ResponseEntity<List<AdminSubscriptionItem>> listSubscriptions(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        verifySuperAdminAccess();

        List<Subscription> subs;
        long totalCount;
        if (page != null || size != null) {
            int safePage = clampPage(page);
            int safeSize = clampSize(size);
            org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(safePage, safeSize);
            org.springframework.data.domain.Page<Subscription> pageResult;
            if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
                try {
                    SubscriptionStatus subStatus = SubscriptionStatus.valueOf(status.toUpperCase().trim());
                    pageResult = subscriptionRepository.findAllByStatusOrderByCreatedAtDesc(subStatus, pageable);
                } catch (IllegalArgumentException e) {
                    pageResult = subscriptionRepository.findAllByOrderByCreatedAtDesc(pageable);
                }
            } else {
                pageResult = subscriptionRepository.findAllByOrderByCreatedAtDesc(pageable);
            }
            subs = pageResult.getContent();
            totalCount = pageResult.getTotalElements();
        } else {
            if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
                try {
                    SubscriptionStatus subStatus = SubscriptionStatus.valueOf(status.toUpperCase().trim());
                    subs = subscriptionRepository.findAllByStatusOrderByCreatedAtDesc(subStatus);
                } catch (IllegalArgumentException e) {
                    subs = subscriptionRepository.findAllByOrderByCreatedAtDesc();
                }
            } else {
                subs = subscriptionRepository.findAllByOrderByCreatedAtDesc();
            }
            totalCount = subs.size();
        }

        LocalDate today = LocalDate.now(clock);
        List<AdminSubscriptionItem> items = new ArrayList<>();
        for (Subscription s : subs) {
            AdminSubscriptionItem item = new AdminSubscriptionItem();
            item.setId(s.getId());
            item.setPlan(s.getPlanType() != null ? s.getPlanType().name() : "UNKNOWN");
            item.setAmount(s.getAmount());
            item.setStartDate(s.getStartDate());
            item.setEndDate(s.getEndDate());
            item.setStatus(s.getStatus() != null ? s.getStatus().name() : "UNKNOWN");
            if (s.getEndDate() != null) {
                long days = ChronoUnit.DAYS.between(today, s.getEndDate());
                item.setDaysRemaining(Math.max(0, days));
            }
            item.setPaymentReference(s.getPaymentReference());
            item.setPaymentUpiId(s.getPaymentUpiId());
            item.setNotes(s.getNotes());
            item.setCreatedAt(s.getCreatedAt());

            User owner = s.getOwner();
            if (owner == null && s.getShop() != null) {
                owner = s.getShop().getOwner();
            }
            if (owner != null) {
                item.setOwnerId(owner.getId());
                item.setOwnerName(owner.getFullName());
                item.setOwnerPhone(owner.getPhone());
                item.setOwnerEmail(owner.getEmail());
            }

            if (s.getShop() != null) {
                item.setShopId(s.getShop().getId());
                item.setShopName(s.getShop().getName());
            }

            items.add(item);
        }
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(totalCount))
                .body(items);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/subscriptions/owner/{ownerId}/history")
    public ResponseEntity<Map<String, Object>> getOwnerSubscriptionAndPaymentHistory(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @PathVariable("ownerId") Long ownerId) {
        verifySuperAdminAccess();

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Owner not found with ID: " + ownerId));

        List<Subscription> subs = subscriptionRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
        LocalDate today = LocalDate.now(clock);
        List<AdminSubscriptionItem> subItems = new ArrayList<>();
        for (Subscription s : subs) {
            AdminSubscriptionItem item = new AdminSubscriptionItem();
            item.setId(s.getId());
            item.setPlan(s.getPlanType() != null ? s.getPlanType().name() : "UNKNOWN");
            item.setAmount(s.getAmount());
            item.setStartDate(s.getStartDate());
            item.setEndDate(s.getEndDate());
            item.setStatus(s.getStatus() != null ? s.getStatus().name() : "UNKNOWN");
            if (s.getEndDate() != null) {
                item.setDaysRemaining(Math.max(0, ChronoUnit.DAYS.between(today, s.getEndDate())));
            }
            item.setPaymentReference(s.getPaymentReference());
            item.setPaymentUpiId(s.getPaymentUpiId());
            item.setNotes(s.getNotes());
            item.setCreatedAt(s.getCreatedAt());
            if (s.getShop() != null) {
                item.setShopId(s.getShop().getId());
                item.setShopName(s.getShop().getName());
            }
            subItems.add(item);
        }

        List<PaymentRecord> payments = paymentRecordRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId);

        return ResponseEntity.ok(Map.of(
                "ownerId", owner.getId(),
                "ownerName", owner.getFullName() != null ? owner.getFullName() : "",
                "phone", owner.getPhone() != null ? owner.getPhone() : "",
                "email", owner.getEmail() != null ? owner.getEmail() : "",
                "subscriptions", subItems,
                "payments", mapPaymentRecords(payments)
        ));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/users-and-shops")
    public ResponseEntity<List<AdminUserShopItem>> listUsersAndShops(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        verifySuperAdminAccess();

        String q = (query != null && !query.isBlank()) ? query.trim().toLowerCase() : null;

        List<User> owners;
        long totalCount;

        if (q == null && (page != null || size != null)) {
            int safePage = clampPage(page);
            int safeSize = clampSize(size);
            org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(safePage, safeSize);
            org.springframework.data.domain.Page<User> pageResult = userRepository.findAllByRoleOrderByCreatedAtDesc(UserRole.ROLE_OWNER, pageable);
            owners = pageResult.getContent();
            totalCount = pageResult.getTotalElements();
        } else {
            owners = userRepository.findAllByRoleOrderByCreatedAtDesc(UserRole.ROLE_OWNER);
            totalCount = owners.size();
        }

        if (owners.isEmpty()) {
            return ResponseEntity.ok()
                    .header("X-Total-Count", "0")
                    .body(Collections.emptyList());
        }

        List<Long> ownerIds = owners.stream().map(User::getId).collect(Collectors.toList());

        // Bulk-fetch shops, businesses, subscriptions to eliminate N+1 queries
        List<Shop> allShops = shopRepository.findAllByOwnerIdInOrderByCreatedAtAsc(ownerIds);
        List<Business> allBusinesses = businessRepository.findAllByOwnerIdInOrderByCreatedAtAsc(ownerIds);
        List<Subscription> allSubs = subscriptionRepository.findAllByOwnerIdInOrderByEndDateDesc(ownerIds);

        Map<Long, List<Shop>> shopsByOwner = allShops.stream()
                .filter(s -> s.getOwner() != null)
                .collect(Collectors.groupingBy(s -> s.getOwner().getId()));

        Map<Long, List<Business>> businessesByOwner = allBusinesses.stream()
                .filter(b -> b.getOwner() != null)
                .collect(Collectors.groupingBy(b -> b.getOwner().getId()));

        Map<Long, Subscription> latestSubByOwner = new HashMap<>();
        for (Subscription s : allSubs) {
            if (s.getOwner() != null) {
                latestSubByOwner.putIfAbsent(s.getOwner().getId(), s);
            }
        }

        List<Long> shopIds = allShops.stream().map(Shop::getId).collect(Collectors.toList());
        Map<Long, String> publicQIdByShop = new HashMap<>();
        if (!shopIds.isEmpty()) {
            List<QueueSession> openSessions = queueSessionRepository.findAllByShopIdInAndStatus(shopIds, QueueSessionStatus.OPEN);
            for (QueueSession qs : openSessions) {
                if (qs.getShop() != null && qs.getPublicQueueId() != null) {
                    publicQIdByShop.putIfAbsent(qs.getShop().getId(), qs.getPublicQueueId());
                }
            }
        }

        List<AdminUserShopItem> items = new ArrayList<>();
        for (User u : owners) {
            List<Shop> shops = shopsByOwner.getOrDefault(u.getId(), Collections.emptyList());
            List<Business> businesses = businessesByOwner.getOrDefault(u.getId(), Collections.emptyList());

            if (q != null) {
                boolean matchesOwner = (u.getFullName() != null && u.getFullName().toLowerCase().contains(q))
                        || (u.getPhone() != null && u.getPhone().contains(q))
                        || (u.getEmail() != null && u.getEmail().toLowerCase().contains(q));
                boolean matchesShop = shops.stream().anyMatch(s ->
                        (s.getName() != null && s.getName().toLowerCase().contains(q))
                        || (s.getBranchName() != null && s.getBranchName().toLowerCase().contains(q))
                );
                if (!matchesOwner && !matchesShop) {
                    continue;
                }
            }

            AdminUserShopItem item = new AdminUserShopItem();
            item.setOwnerId(u.getId());
            item.setOwnerName(u.getFullName());
            item.setPhone(u.getPhone());
            item.setEmail(u.getEmail());
            item.setRegisteredAt(u.getCreatedAt());

            Subscription sub = latestSubByOwner.get(u.getId());
            if (sub != null) {
                item.setSubscriptionPlan(sub.getPlanType() != null ? sub.getPlanType().name() : "NONE");
                item.setSubscriptionStatus(sub.getStatus() != null ? sub.getStatus().name() : "NONE");
                item.setSubscriptionEndDate(sub.getEndDate());
            } else {
                item.setSubscriptionPlan("NONE");
                item.setSubscriptionStatus("EXPIRED");
            }

            List<AdminUserShopItem.AdminBusinessSummary> bList = new ArrayList<>();
            for (Business b : businesses) {
                bList.add(new AdminUserShopItem.AdminBusinessSummary(b.getId(), b.getName()));
            }
            item.setBusinesses(bList);

            List<AdminUserShopItem.AdminBranchSummary> branchList = new ArrayList<>();
            for (Shop s : shops) {
                AdminUserShopItem.AdminBranchSummary bs = new AdminUserShopItem.AdminBranchSummary();
                bs.setId(s.getId());
                bs.setName(s.getName());
                bs.setBranchName(s.getBranchName());
                bs.setBusinessName(s.getBusiness() != null ? s.getBusiness().getName() : s.getName());
                bs.setCategory(s.getCategory());
                bs.setPublicQueueId(publicQIdByShop.get(s.getId()));
                bs.setPrimary(s.isPrimary());
                bs.setRegistrationStatus(s.getRegistrationStatus());
                bs.setAddress(s.getAddress());
                branchList.add(bs);
            }
            item.setBranches(branchList);

            items.add(item);
        }

        if (q != null && (page != null || size != null)) {
            totalCount = items.size();
            int safePage = clampPage(page);
            int safeSize = clampSize(size);
            int fromIndex = Math.min(safePage * safeSize, items.size());
            int toIndex = Math.min(fromIndex + safeSize, items.size());
            items = items.subList(fromIndex, toIndex);
        } else if (q != null) {
            totalCount = items.size();
        }

        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(totalCount))
                .body(items);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/activity-logs")
    public ResponseEntity<List<AdminActivityLogItem>> listActivityLogs(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        verifySuperAdminAccess();

        List<AuditLog> logs;
        long totalCount;
        if (page != null || size != null) {
            int safePage = clampPage(page);
            int safeSize = clampSize(size);
            org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(safePage, safeSize);
            org.springframework.data.domain.Page<AuditLog> pageResult = auditLogRepository.findAllByTargetTypeNotOrderByCreatedAtDesc("BRANCH_IDEMPOTENCY", pageable);
            logs = pageResult.getContent();
            totalCount = pageResult.getTotalElements();
        } else {
            logs = auditLogRepository.findTop50ByTargetTypeNotOrderByCreatedAtDesc("BRANCH_IDEMPOTENCY");
            totalCount = logs.size();
        }

        List<AdminActivityLogItem> items = new ArrayList<>();
        for (AuditLog l : logs) {
            items.add(new AdminActivityLogItem(
                    l.getId(),
                    l.getAction(),
                    l.getActor(),
                    l.getTargetType(),
                    l.getTargetId(),
                    l.getDetails(),
                    l.getCreatedAt()
            ));
        }
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(totalCount))
                .body(items);
    }
}
