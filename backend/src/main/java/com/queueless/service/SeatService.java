package com.queueless.service;

import com.queueless.dto.PurchaseSeatRequest;
import com.queueless.dto.SeatSummaryResponse;
import com.queueless.dto.SubscriptionSeatResponse;
import com.queueless.entity.*;
import com.queueless.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class SeatService {

    private static final Logger log = LoggerFactory.getLogger(SeatService.class);

    public static final BigDecimal SEAT_MONTHLY_PRICE = new BigDecimal("47.00");
    public static final BigDecimal SEAT_YEARLY_PRICE = new BigDecimal("494.00");

    private final SubscriptionSeatRepository seatRepository;
    private final UserRepository userRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final AuditLogRepository auditLogRepository;
    private final SubscriptionService subscriptionService;
    private Clock clock;

    public SeatService(SubscriptionSeatRepository seatRepository,
                       UserRepository userRepository,
                       PaymentRecordRepository paymentRecordRepository,
                       AuditLogRepository auditLogRepository,
                       SubscriptionService subscriptionService,
                       Clock clock) {
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.auditLogRepository = auditLogRepository;
        this.subscriptionService = subscriptionService;
        this.clock = clock;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SeatSummaryResponse getSeatSummary(Long ownerId) {
        LocalDate today = LocalDate.now(clock);
        List<SubscriptionSeat> seats = seatRepository.findAllByOwnerId(ownerId);

        long activeAdditionalSeats = 0;
        List<SubscriptionSeatResponse> seatResponses = new ArrayList<>();

        for (SubscriptionSeat s : seats) {
            boolean isActive = s.getStatus() == SubscriptionStatus.ACTIVE &&
                    s.getEndDate() != null && !today.isAfter(s.getEndDate());
            if (isActive) {
                activeAdditionalSeats++;
            }

            SubscriptionSeatResponse resp = new SubscriptionSeatResponse();
            resp.setId(s.getId());
            resp.setPlanType(s.getPlanType() != null ? s.getPlanType().name() : null);
            resp.setStatus(s.getStatus() != null ? s.getStatus().name() : null);
            resp.setStartDate(s.getStartDate());
            resp.setEndDate(s.getEndDate());
            resp.setAmount(s.getAmount());
            resp.setPaymentReference(s.getPaymentReference());
            resp.setActive(isActive);

            if (s.getAssignedUser() != null) {
                resp.setAssignedUserId(s.getAssignedUser().getId());
                resp.setAssignedUserName(s.getAssignedUser().getFullName());
                resp.setAssignedUserPhone(s.getAssignedUser().getPhone());
            }
            seatResponses.add(resp);
        }

        // Active staff members managed by this owner
        long activeAssignedStaff = userRepository.countByOwnerIdAndRoleAndStatus(ownerId, UserRole.ROLE_STAFF, "ACTIVE");

        SeatSummaryResponse summary = new SeatSummaryResponse();
        summary.setIncludedSeats(1);
        summary.setAdditionalSeatsPurchased(activeAdditionalSeats);
        summary.setTotalActiveSeats(1 + activeAdditionalSeats);
        summary.setAssignedSeats(activeAssignedStaff);
        long available = Math.max(0, (1 + activeAdditionalSeats) - activeAssignedStaff);
        summary.setAvailableSeats(available);
        summary.setMonthlyPrice(SEAT_MONTHLY_PRICE);
        summary.setYearlyPrice(SEAT_YEARLY_PRICE);
        summary.setConfiguredUpiId(subscriptionService.getConfiguredUpiId());
        summary.setConfiguredUpiName(subscriptionService.getConfiguredUpiName());
        summary.setSeats(seatResponses);

        return summary;
    }

    @Transactional
    public PaymentRecord purchaseSeat(Long ownerId, PurchaseSeatRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Owner not found: " + ownerId));

        String utr = request.getPaymentReference().trim();
        if (paymentRecordRepository.existsByPaymentReferenceAndStatus(utr, "APPROVED")) {
            throw new IllegalArgumentException("This payment reference / UTR has already been approved for another payment.");
        }

        BigDecimal amount = request.getPlanType() == SubscriptionPlan.YEARLY ? SEAT_YEARLY_PRICE : SEAT_MONTHLY_PRICE;
        String upiId = (request.getPaymentUpiId() != null && !request.getPaymentUpiId().isBlank())
                ? request.getPaymentUpiId().trim()
                : subscriptionService.getConfiguredUpiId();

        PaymentRecord record = new PaymentRecord(
                "USER_SEAT",
                owner,
                null,
                null,
                amount,
                utr,
                upiId
        );
        record.setPlanType(request.getPlanType().name());
        record.setNotes("Additional user seat (" + request.getPlanType().name() + ") payment");
        record = paymentRecordRepository.save(record);

        auditLogRepository.save(new AuditLog(
                "SUBMIT_SEAT_PAYMENT",
                owner.getPhone() != null ? owner.getPhone() : owner.getEmail(),
                "PAYMENT",
                record.getId().toString(),
                "Submitted payment for additional " + request.getPlanType().name() + " user seat (\u20B9" + amount + ") with UTR " + utr,
                null
        ));

        return record;
    }

    @Transactional
    public SubscriptionSeat assignAvailableSeat(Long ownerId, User staffUser) {
        LocalDate today = LocalDate.now(clock);
        List<SubscriptionSeat> seats = seatRepository.findAllByOwnerId(ownerId);

        SubscriptionSeat targetSeat = null;
        for (SubscriptionSeat s : seats) {
            boolean isActive = s.getStatus() == SubscriptionStatus.ACTIVE &&
                    s.getEndDate() != null && !today.isAfter(s.getEndDate());
            if (isActive && s.getAssignedUser() == null) {
                targetSeat = s;
                break;
            }
        }

        if (targetSeat != null) {
            targetSeat.setAssignedUser(staffUser);
            return seatRepository.save(targetSeat);
        }

        // Check if covered by the 1 included seat
        long activeAssignedStaff = userRepository.countByOwnerIdAndRoleAndStatus(ownerId, UserRole.ROLE_STAFF, "ACTIVE");
        if (activeAssignedStaff <= 1) {
            // Staff is assigned to the included seat; no separate SubscriptionSeat entity row needed
            return null;
        }

        throw new IllegalStateException("No available user seats. Please purchase an additional user seat (\u20B947/month or \u20B9494/year) before adding more staff.");
    }

    @Transactional
    public void unassignSeat(User staffUser) {
        seatRepository.findByAssignedUserIdAndStatus(staffUser.getId(), SubscriptionStatus.ACTIVE)
                .ifPresent(seat -> {
                    seat.setAssignedUser(null);
                    seatRepository.save(seat);
                });
    }
}
