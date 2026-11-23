package com.queueless.service;

import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.repository.BusinessCategoryChangeAuditRepository;
import com.queueless.repository.BusinessCategoryChangeRequestRepository;
import com.queueless.repository.ShopRepository;
import com.queueless.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class CategoryChangeService {

    private static final Logger log = LoggerFactory.getLogger(CategoryChangeService.class);
    private static final Pattern UTR_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{6,35}$");

    private final BusinessCategoryChangeRequestRepository requestRepository;
    private final BusinessCategoryChangeAuditRepository auditRepository;
    private final ShopRepository shopRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final com.queueless.repository.PaymentRecordRepository paymentRecordRepository;
    private final Clock clock;

    public CategoryChangeService(BusinessCategoryChangeRequestRepository requestRepository,
                                 BusinessCategoryChangeAuditRepository auditRepository,
                                 ShopRepository shopRepository,
                                 SubscriptionRepository subscriptionRepository,
                                 SubscriptionService subscriptionService,
                                 com.queueless.repository.PaymentRecordRepository paymentRecordRepository,
                                 Clock clock) {
        this.requestRepository = requestRepository;
        this.auditRepository = auditRepository;
        this.shopRepository = shopRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionService = subscriptionService;
        this.paymentRecordRepository = paymentRecordRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CategoryChangeStatusResponse getStatus(Long shopId) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + shopId));

        CategoryChangeStatusResponse res = new CategoryChangeStatusResponse();
        res.setCurrentCategory(shop.getCategory());
        res.setFee(BusinessCategoryConstants.CATEGORY_CHANGE_FEE);

        String upiId = subscriptionService.getConfiguredUpiId();
        String upiName = subscriptionService.getConfiguredUpiName();
        res.setUpiId(upiId);
        res.setUpiName(upiName);
        res.setAllowedCategories(BusinessCategoryConstants.VALID_CATEGORIES);

        // QR code payload for ₹101
        String qr = String.format("upi://pay?pa=%s&pn=%s&am=101.00&cu=INR&tn=CategoryChange_Shop%d",
                upiId, upiName.replace(" ", "%20"), shopId);
        res.setQrPayload(qr);

        // Check active / pending requests
        requestRepository.findTopByShopIdOrderByCreatedAtDesc(shopId).ifPresent(req -> {
            res.setRequestId(req.getId());
            res.setOldCategory(req.getOldCategory());
            res.setRequestedCategory(req.getRequestedCategory());
            res.setAmount(req.getAmount());
            res.setPaymentReference(req.getPaymentReference());
            res.setStatus(req.getStatus());
            res.setRejectionReason(req.getRejectionReason());
            res.setCreatedAt(req.getCreatedAt());
            res.setReviewedAt(req.getReviewedAt());

            boolean isPending = req.getStatus() == CategoryChangeStatus.PAYMENT_PENDING
                    || req.getStatus() == CategoryChangeStatus.PENDING_VERIFICATION;
            res.setHasPendingRequest(isPending);
        });

        return res;
    }

    @Transactional
    public CategoryChangeStatusResponse initiateRequest(Long shopId, Long ownerId, CategoryChangeInitiateRequest request) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + shopId));

        String reqCategory = request.getRequestedCategory();
        if (reqCategory == null || reqCategory.isBlank()) {
            throw new IllegalArgumentException("Requested category is required.");
        }
        reqCategory = reqCategory.trim();

        if (!BusinessCategoryConstants.isValidCategory(reqCategory)) {
            throw new IllegalArgumentException("Requested category '" + reqCategory + "' is not a supported business category.");
        }

        if (reqCategory.equalsIgnoreCase(shop.getCategory())) {
            throw new IllegalArgumentException("Requested category must be different from current registered category (" + shop.getCategory() + ").");
        }

        // Check if an existing request is pending
        boolean hasPending = requestRepository.existsByShopIdAndStatusIn(
                shopId,
                List.of(CategoryChangeStatus.PAYMENT_PENDING, CategoryChangeStatus.PENDING_VERIFICATION)
        );
        if (hasPending) {
            throw new IllegalStateException("A category change request is already awaiting payment or verification for this shop.");
        }

        BusinessCategoryChangeRequest changeRequest = new BusinessCategoryChangeRequest(
                shopId,
                ownerId,
                shop.getCategory(),
                reqCategory
        );
        // Backend strictly enforces ₹101 fee regardless of what any client submits
        changeRequest.setAmount(BusinessCategoryConstants.CATEGORY_CHANGE_FEE);
        changeRequest.setStatus(CategoryChangeStatus.PAYMENT_PENDING);

        requestRepository.save(changeRequest);
        log.info("Category change request #{} initiated for shop #{}: {} -> {}",
                changeRequest.getId(), shopId, shop.getCategory(), reqCategory);

        return getStatus(shopId);
    }

    @Transactional
    public CategoryChangeStatusResponse submitPayment(Long shopId, Long ownerId, CategoryChangePaymentSubmitRequest request) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + shopId));

        BusinessCategoryChangeRequest changeRequest = requestRepository.findById(request.getRequestId())
                .orElseThrow(() -> new IllegalArgumentException("Category change request not found with ID: " + request.getRequestId()));

        if (!changeRequest.getShopId().equals(shopId) || !changeRequest.getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException("You are not authorized to submit payment for this request.");
        }

        if (changeRequest.getStatus() == CategoryChangeStatus.APPROVED) {
            throw new IllegalStateException("This category change request has already been approved.");
        }
        if (changeRequest.getStatus() == CategoryChangeStatus.REJECTED) {
            throw new IllegalStateException("This category change request was rejected. Please initiate a new request.");
        }

        String rawUtr = request.getPaymentReference();
        if (rawUtr == null || rawUtr.isBlank()) {
            throw new IllegalArgumentException("Payment reference (UTR) is required.");
        }
        String cleanUtr = rawUtr.trim();

        if (!UTR_PATTERN.matcher(cleanUtr).matches()) {
            throw new IllegalArgumentException("Invalid UTR reference format. It must be 6 to 30 alphanumeric characters.");
        }

        // Cross-payment integrity: UTR cannot be reused across category change requests
        if (requestRepository.existsByPaymentReferenceAndIdNot(cleanUtr, changeRequest.getId())) {
            throw new IllegalArgumentException("This payment reference (UTR) has already been submitted for another category change request.");
        }

        // Cross-payment integrity: UTR cannot be reused from subscription payments
        if (subscriptionRepository.existsByPaymentReference(cleanUtr)) {
            throw new IllegalArgumentException("This payment reference (UTR) is already registered for a subscription payment and cannot be reused for a category change.");
        }

        changeRequest.setPaymentReference(cleanUtr);
        changeRequest.setStatus(CategoryChangeStatus.PENDING_VERIFICATION);
        requestRepository.save(changeRequest);

        // Record unified PaymentRecord for Super Admin
        com.queueless.entity.PaymentRecord payRec = new com.queueless.entity.PaymentRecord(
                "CATEGORY_CHANGE",
                shop.getOwner(),
                shop,
                null,
                changeRequest.getAmount(),
                cleanUtr,
                subscriptionService.getConfiguredUpiId()
        );
        payRec.setNotes("Category change request for " + shop.getName() + " (" + changeRequest.getOldCategory() + " -> " + changeRequest.getRequestedCategory() + ")");
        paymentRecordRepository.save(payRec);

        log.info("Payment reference '{}' submitted for category change request #{}", cleanUtr, changeRequest.getId());

        // Category remains UNCHANGED until admin approval!
        return getStatus(shopId);
    }

    @Transactional(readOnly = true)
    public List<AdminCategoryChangeItem> adminListRequests() {
        List<BusinessCategoryChangeRequest> requests = requestRepository.findAllByOrderByCreatedAtDesc();
        List<AdminCategoryChangeItem> items = new ArrayList<>();

        for (BusinessCategoryChangeRequest r : requests) {
            AdminCategoryChangeItem item = new AdminCategoryChangeItem();
            item.setId(r.getId());
            item.setShopId(r.getShopId());
            item.setOldCategory(r.getOldCategory());
            item.setRequestedCategory(r.getRequestedCategory());
            item.setAmount(r.getAmount());
            item.setPaymentReference(r.getPaymentReference());
            item.setStatus(r.getStatus());
            item.setRejectionReason(r.getRejectionReason());
            item.setCreatedAt(r.getCreatedAt());
            item.setReviewedAt(r.getReviewedAt());
            item.setReviewedBy(r.getReviewedBy());

            shopRepository.findById(r.getShopId()).ifPresent(s -> {
                item.setShopName(s.getName());
                if (s.getOwner() != null) {
                    item.setOwnerName(s.getOwner().getFullName());
                    item.setOwnerEmail(s.getOwner().getEmail());
                    item.setOwnerPhone(s.getOwner().getPhone());
                }
            });

            items.add(item);
        }

        return items;
    }

    @Transactional
    public void adminApproveRequest(Long requestId, String adminIdentifier) {
        BusinessCategoryChangeRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found with ID: " + requestId));

        if (req.getStatus() == CategoryChangeStatus.APPROVED) {
            throw new IllegalStateException("Request has already been approved.");
        }
        if (req.getStatus() == CategoryChangeStatus.REJECTED) {
            throw new IllegalStateException("Cannot approve a rejected category change request.");
        }

        if (req.getPaymentReference() == null || req.getPaymentReference().isBlank()) {
            throw new IllegalStateException("Cannot approve request without a payment reference (UTR).");
        }

        Shop shop = shopRepository.findById(req.getShopId())
                .orElseThrow(() -> new IllegalArgumentException("Shop not found with ID: " + req.getShopId()));

        // Prevent owner from self-approving their own category change request
        if (shop.getOwner() != null && shop.getOwner().getEmail().equalsIgnoreCase(adminIdentifier)) {
            throw new AccessDeniedException("Shop owner cannot self-approve their own category change request.");
        }

        String oldCategory = shop.getCategory();
        String newCategory = req.getRequestedCategory();
        LocalDateTime now = LocalDateTime.now(clock);

        // Update ONLY category on shop. All other shop, queue, and subscription fields remain intact!
        shop.setCategory(newCategory);
        shopRepository.save(shop);

        req.setStatus(CategoryChangeStatus.APPROVED);
        req.setReviewedAt(now);
        req.setReviewedBy(adminIdentifier);
        requestRepository.save(req);

        // Record immutable audit entry
        BusinessCategoryChangeAudit audit = new BusinessCategoryChangeAudit(
                shop.getId(),
                oldCategory,
                newCategory,
                req.getAmount(),
                req.getId(),
                adminIdentifier,
                now
        );
        auditRepository.save(audit);

        // Keep unified PaymentRecord in sync
        if (req.getPaymentReference() != null) {
            paymentRecordRepository.findByPaymentReference(req.getPaymentReference()).ifPresent(p -> {
                p.setStatus("APPROVED");
                p.setReviewedAt(now);
                p.setReviewedBy(adminIdentifier);
                paymentRecordRepository.save(p);
            });
        }

        log.info("Category change request #{} APPROVED by {}. Shop #{} category updated: '{}' -> '{}'",
                req.getId(), adminIdentifier, shop.getId(), oldCategory, newCategory);
    }

    @Transactional
    public void adminRejectRequest(Long requestId, AdminCategoryRejectRequest request, String adminIdentifier) {
        BusinessCategoryChangeRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found with ID: " + requestId));

        if (req.getStatus() == CategoryChangeStatus.APPROVED) {
            throw new IllegalStateException("Cannot reject an already approved category change request.");
        }

        String reason = (request != null && request.getReason() != null && !request.getReason().isBlank())
                ? request.getReason().trim()
                : "Payment verification failed or invalid UTR reference.";

        LocalDateTime now = LocalDateTime.now(clock);
        req.setStatus(CategoryChangeStatus.REJECTED);
        req.setRejectionReason(reason);
        req.setReviewedAt(now);
        req.setReviewedBy(adminIdentifier);
        requestRepository.save(req);

        // Keep unified PaymentRecord in sync
        if (req.getPaymentReference() != null) {
            paymentRecordRepository.findByPaymentReference(req.getPaymentReference()).ifPresent(p -> {
                p.setStatus("REJECTED");
                p.setRejectionReason(reason);
                p.setReviewedAt(now);
                p.setReviewedBy(adminIdentifier);
                paymentRecordRepository.save(p);
            });
        }

        // Original shop category is strictly preserved!
        log.info("Category change request #{} REJECTED by {}. Reason: {}",
                req.getId(), adminIdentifier, reason);
    }
}
