package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.repository.*;
import com.queueless.service.AuthService;
import com.queueless.service.BranchService;
import com.queueless.service.EntitlementService;
import com.queueless.service.SeatService;
import com.queueless.service.StaffService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MultiBranchAndUserSeatsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private ShopMembershipRepository membershipRepository;

    @Autowired
    private SubscriptionSeatRepository seatRepository;

    @Autowired
    private PaymentRecordRepository paymentRecordRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private TrialEligibilityLedgerRepository trialEligibilityLedgerRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private BranchService branchService;

    @Autowired
    private SeatService seatService;

    @Autowired
    private StaffService staffService;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private com.queueless.security.JwtTokenProvider tokenProvider;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private String superAdminToken;
    private String ownerToken;
    private User ownerUser;
    private Shop primaryShop;

    @BeforeEach
    void setUp() {
        String phone = "9811122233";
        userRepository.findByPhone("+91" + phone).ifPresent(u -> {
            try {
                authService.deleteAccount(u.getPhone());
            } catch (Exception ignored) {}
        });
        trialEligibilityLedgerRepository.deleteAll();

        String pin = "123456";
        RegisterRequest reg = new RegisterRequest();
        reg.setPhone(phone);
        reg.setPin(pin);
        reg.setConfirmPin(pin);
        reg.setFullName("MultiBiz Owner");
        reg.setShopName("Apex Enterprises");
        reg.setCategory("BARBER");
        reg.setDeviceFingerprint("fp-multibiz-1");

        AuthResponse res = authService.register(reg);
        ownerToken = res.getToken();
        ownerUser = userRepository.findByPhone("+91" + phone).orElseThrow();
        primaryShop = shopRepository.findByOwnerId(ownerUser.getId()).orElseThrow();

        User sa = new User("sa_" + System.currentTimeMillis() + "@test.com", passwordEncoder.encode("Pass123!"), "Super Admin", "+9197" + (System.currentTimeMillis() % 100000000), UserRole.ROLE_SUPER_ADMIN);
        sa.setStatus("ACTIVE");
        sa.setActiveSessionId(java.util.UUID.randomUUID().toString());
        sa = userRepository.save(sa);
        superAdminToken = tokenProvider.generateTokenForUsername(sa.getEmail(), sa.getActiveSessionId());
    }

    @AfterEach
    void tearDown() {
        if (ownerUser != null && ownerUser.getId() != null) {
            // Delete staff users associated with owner
            List<User> staffUsers = userRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerUser.getId());
            for (User s : staffUsers) {
                seatService.unassignSeat(s);
                userSessionRepository.deleteAll(userSessionRepository.findAllByUserId(s.getId()));
                membershipRepository.deleteAll(membershipRepository.findAllByUserId(s.getId()));
                userRepository.delete(s);
            }

            try {
                authService.deleteAccount(ownerUser.getPhone());
            } catch (Exception ignored) {}
            trialEligibilityLedgerRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("Multi-Business & Multi-Branch: Primary shop is REGISTERED; second branch requires ₹99 registration")
    void testPrimaryBranchIncludedSecondBranchRequiresPayment() throws Exception {
        assertTrue(primaryShop.getIsPrimary(), "Primary shop must have is_primary=true");
        assertEquals("REGISTERED", primaryShop.getRegistrationStatus(), "Primary shop must be REGISTERED");

        // Owner creates second branch under same business
        CreateBranchRequest branchReq = new CreateBranchRequest(
                primaryShop.getBusiness().getId(),
                "Apex Downtown",
                "Downtown Branch",
                "123 Main St",
                "9811122244",
                "BARBER",
                15
        );

        String jsonReq = objectMapper.writeValueAsString(branchReq);
        String response = mockMvc.perform(post("/api/owner/branches")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonReq))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        BranchSummaryResponse branch = objectMapper.readValue(response, BranchSummaryResponse.class);
        assertNotNull(branch.getId());
        assertFalse(branch.getIsPrimary(), "Second branch must NOT be primary");
        assertEquals("PAYMENT_PENDING", branch.getRegistrationStatus(), "Second branch must be PAYMENT_PENDING");

        // Submit branch registration payment
        BranchRegistrationPaymentRequest payReq = new BranchRegistrationPaymentRequest("UTR-BRANCH-99-TEST", "absunny@ybl");
        mockMvc.perform(post("/api/owner/branches/" + branch.getId() + "/payment")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // Admin approves branch registration
        PaymentRecord payment = paymentRecordRepository.findByPaymentReference("UTR-BRANCH-99-TEST").orElseThrow();
        mockMvc.perform(post("/api/admin/payments/" + payment.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Payment approved successfully."));

        // Branch status is now REGISTERED
        Shop updatedBranch = shopRepository.findById(branch.getId()).orElseThrow();
        assertEquals("REGISTERED", updatedBranch.getRegistrationStatus());
        assertEquals(new BigDecimal("99.00"), updatedBranch.getRegistrationFeePaid());
    }

    @Test
    @DisplayName("Subscription Decoupling: Main subscription covers all registered branches")
    void testSubscriptionDecouplingAcrossBranches() {
        // Primary shop has active trial
        assertTrue(entitlementService.hasActiveEntitlement(primaryShop.getId()));

        // Create second registered branch
        Shop branch2 = new Shop();
        branch2.setOwner(ownerUser);
        branch2.setBusiness(primaryShop.getBusiness());
        branch2.setName("Apex Uptown");
        branch2.setBranchName("Uptown");
        branch2.setCategory("BARBER");
        branch2.setIsPrimary(false);
        branch2.setRegistrationStatus("REGISTERED");
        branch2.setRegistrationFeePaid(new BigDecimal("99.00"));
        branch2 = shopRepository.save(branch2);

        // Both shops are eligible because owner has active entitlement
        assertTrue(entitlementService.hasActiveEntitlement(primaryShop.getId()));
        assertTrue(entitlementService.hasActiveEntitlement(branch2.getId()));

        // Expire subscription
        List<Subscription> subs = subscriptionRepository.findByOwnerIdOrderByCreatedAtDesc(ownerUser.getId());
        for (Subscription s : subs) {
            s.setStatus(SubscriptionStatus.EXPIRED);
            s.setEndDate(LocalDate.now().minusDays(1));
            subscriptionRepository.save(s);
        }

        // Both shops are now restricted
        assertFalse(entitlementService.hasActiveEntitlement(primaryShop.getId()));
        assertFalse(entitlementService.hasActiveEntitlement(branch2.getId()));

        // Renew main subscription (₹51 / 30 days)
        Subscription renewed = new Subscription(
                primaryShop,
                SubscriptionPlan.MONTHLY,
                SubscriptionStatus.ACTIVE,
                LocalDate.now(),
                LocalDate.now().plusDays(30),
                new BigDecimal("51.00")
        );
        renewed.setPaymentReference("UTR-RENEW-51");
        renewed.setOwner(ownerUser);
        subscriptionRepository.save(renewed);

        // Both shops immediately regain access without paying ₹99 again!
        assertTrue(entitlementService.hasActiveEntitlement(primaryShop.getId()));
        assertTrue(entitlementService.hasActiveEntitlement(branch2.getId()));
        assertEquals(new BigDecimal("99.00"), branch2.getRegistrationFeePaid());
    }

    @Test
    @DisplayName("User Seats & Staff Management: 1 included seat; additional seat purchased & reassigned on revoke")
    void testStaffSeatsAndReassignment() throws Exception {
        // Seat summary initially: 1 included, 0 additional, 0 assigned, 1 available
        SeatSummaryResponse seats = seatService.getSeatSummary(ownerUser.getId());
        assertEquals(1, seats.getIncludedSeats());
        assertEquals(0, seats.getAdditionalSeatsPurchased());
        assertEquals(0, seats.getAssignedSeats());
        assertEquals(1, seats.getAvailableSeats());

        // Create staff member 1 using the 1 available seat
        CreateStaffRequest staffReq = new CreateStaffRequest(
                "Staff Member 1",
                "9811122288",
                "654321",
                List.of(primaryShop.getId())
        );

        StaffSummaryResponse staff1 = staffService.createStaff(ownerUser.getId(), staffReq);
        assertNotNull(staff1.getId());
        assertEquals("Staff Member 1", staff1.getFullName());

        // Now 0 seats available
        seats = seatService.getSeatSummary(ownerUser.getId());
        assertEquals(1, seats.getAssignedSeats());
        assertEquals(0, seats.getAvailableSeats());

        // Attempting to create staff member 2 fails with 400 No seats available
        CreateStaffRequest staffReq2 = new CreateStaffRequest(
                "Staff Member 2",
                "9811122299",
                "654321",
                List.of(primaryShop.getId())
        );
        assertThrows(IllegalArgumentException.class, () -> staffService.createStaff(ownerUser.getId(), staffReq2));

        // Owner purchases additional monthly user seat (₹47)
        PurchaseSeatRequest seatBuy = new PurchaseSeatRequest(SubscriptionPlan.MONTHLY, "UTR-SEAT-47-TEST", "absunny@ybl");
        mockMvc.perform(post("/api/owner/seats/purchase")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(seatBuy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // Admin approves seat payment
        PaymentRecord seatPayment = paymentRecordRepository.findByPaymentReference("UTR-SEAT-47-TEST").orElseThrow();
        mockMvc.perform(post("/api/admin/payments/" + seatPayment.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk());

        // Seat is now active, so available seats = 1
        seats = seatService.getSeatSummary(ownerUser.getId());
        assertEquals(1, seats.getAdditionalSeatsPurchased());
        assertEquals(1, seats.getAvailableSeats());

        // Now staff member 2 can be created!
        StaffSummaryResponse staff2 = staffService.createStaff(ownerUser.getId(), staffReq2);
        assertNotNull(staff2.getId());

        // When staff1 is revoked, their seat is freed for reassignment
        staffService.revokeStaff(ownerUser.getId(), staff1.getId());
        seats = seatService.getSeatSummary(ownerUser.getId());
        assertEquals(1, seats.getAvailableSeats(), "Revoking staff must free seat for reassignment");
    }

    @Test
    @DisplayName("Staff Authorization & Device Isolation: Staff can only access assigned branch and 1 active session")
    void testStaffBranchIsolationAndDeviceLimit() throws Exception {
        // Create second branch
        Shop branch2 = new Shop();
        branch2.setOwner(ownerUser);
        branch2.setBusiness(primaryShop.getBusiness());
        branch2.setName("Apex West");
        branch2.setBranchName("West");
        branch2.setCategory("BARBER");
        branch2.setIsPrimary(false);
        branch2.setRegistrationStatus("REGISTERED");
        branch2 = shopRepository.save(branch2);

        // Create staff member assigned ONLY to branch2
        CreateStaffRequest staffReq = new CreateStaffRequest(
                "Staff Operator",
                "9811122277",
                "112233",
                List.of(branch2.getId())
        );
        StaffSummaryResponse staff = staffService.createStaff(ownerUser.getId(), staffReq);

        // Clear temporary PIN so staff member can perform standard routine PIN login
        User staffUser = userRepository.findById(staff.getId()).orElseThrow();
        staffUser.setTemporaryPin(false);
        userRepository.save(staffUser);

        // Staff logs in via PIN
        AuthResponse staffLogin1 = authService.pinLogin(
                new PinLoginRequest("9811122277", "112233", "device-phone-1"),
                "127.0.0.1"
        );
        String staffToken1 = staffLogin1.getToken();
        assertEquals(UserRole.ROLE_STAFF, staffLogin1.getRole());

        // Staff can access assigned branch2
        mockMvc.perform(get("/api/queue/dashboard?shopId=" + branch2.getId())
                        .header("Authorization", "Bearer " + staffToken1))
                .andExpect(status().isOk());

        // Staff CANNOT access primaryShop (403 Forbidden)
        mockMvc.perform(get("/api/queue/dashboard?shopId=" + primaryShop.getId())
                        .header("Authorization", "Bearer " + staffToken1))
                .andExpect(status().isForbidden());

        // Staff CANNOT access billing or staff management endpoints (403 Forbidden)
        mockMvc.perform(get("/api/owner/staff")
                        .header("Authorization", "Bearer " + staffToken1))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/owner/seats")
                        .header("Authorization", "Bearer " + staffToken1))
                .andExpect(status().isForbidden());

        // Single active device: Staff logs in from device 2
        AuthResponse staffLogin2 = authService.pinLogin(
                new PinLoginRequest("9811122277", "112233", "device-tablet-2"),
                "127.0.0.1"
        );
        String staffToken2 = staffLogin2.getToken();

        // Device 2 works
        mockMvc.perform(get("/api/queue/dashboard?shopId=" + branch2.getId())
                        .header("Authorization", "Bearer " + staffToken2))
                .andExpect(status().isOk());

        // Device 1 is now rejected (session invalid -> 401/403)
        mockMvc.perform(get("/api/queue/dashboard?shopId=" + branch2.getId())
                        .header("Authorization", "Bearer " + staffToken1))
                .andExpect(status().is4xxClientError());

        // Owner revokes staff
        mockMvc.perform(post("/api/owner/staff/" + staff.getId() + "/revoke")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        // Device 2 is immediately rejected (revoked -> 401/403)
        mockMvc.perform(get("/api/queue/dashboard?shopId=" + branch2.getId())
                        .header("Authorization", "Bearer " + staffToken2))
                .andExpect(status().is4xxClientError());
    }
}
