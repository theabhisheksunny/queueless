package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.exception.DeviceMismatchException;
import com.queueless.repository.*;
import com.queueless.security.DeviceCryptoUtil;
import com.queueless.service.*;
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DeviceBoundAuthAndBulkRegistrationSecurityTest {

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
    private DeviceRegistrationRepository deviceRegistrationRepository;

    @Autowired
    private DeviceReplacementRequestRepository replacementRequestRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private BranchService branchService;

    @Autowired
    private StaffService staffService;

    @Autowired
    private SeatService seatService;

    @Autowired
    private TrialEligibilityLedgerRepository trialEligibilityLedgerRepository;

    @Autowired
    private DeviceAuthService deviceAuthService;

    @Autowired
    private DeviceCryptoUtil deviceCryptoUtil;

    @Autowired
    private com.queueless.security.JwtTokenProvider tokenProvider;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private String superAdminToken;
    private String ownerToken;
    private User ownerUser;
    private Shop primaryShop;

    private KeyPair generateTestEcKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        ECGenParameterSpec ecSpec = new ECGenParameterSpec("secp256r1");
        kpg.initialize(ecSpec);
        return kpg.generateKeyPair();
    }

    private String signPayload(KeyPair kp, String payload) throws Exception {
        Signature ecdsa = Signature.getInstance("SHA256withECDSAinP1363Format");
        ecdsa.initSign(kp.getPrivate());
        ecdsa.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] sig = ecdsa.sign();
        return Base64.getEncoder().encodeToString(sig);
    }

    private void cleanupOwnerAndStaff(Long ownerId, String ownerPhone) {
        if (ownerId != null) {
            replacementRequestRepository.deleteAll();
            deviceRegistrationRepository.deleteAll();
            List<User> staffUsers = userRepository.findAllByOwnerIdOrderByCreatedAtAsc(ownerId);
            for (User s : staffUsers) {
                seatService.unassignSeat(s);
                userSessionRepository.deleteAll(userSessionRepository.findAllByUserId(s.getId()));
                membershipRepository.deleteAll(membershipRepository.findAllByUserId(s.getId()));
                userRepository.delete(s);
            }
        }
        if (ownerPhone != null) {
            try {
                authService.deleteAccount(ownerPhone);
            } catch (Exception ignored) {}
        }
        trialEligibilityLedgerRepository.deleteAll();
    }

    @BeforeEach
    void setUp() throws Exception {
        String phone = "9822233344";
        userRepository.findByPhone("+91" + phone).ifPresent(u -> cleanupOwnerAndStaff(u.getId(), u.getPhone()));
        trialEligibilityLedgerRepository.deleteAll();

        RegisterRequest reg = new RegisterRequest();
        reg.setPhone(phone);
        reg.setPin("123456");
        reg.setConfirmPin("123456");
        reg.setFullName("Test Owner");
        reg.setShopName("Main Salon");
        reg.setCategory("Salon & Spa");
        reg.setAvgServiceDurationMinutes(20);

        AuthResponse resp = authService.register(reg);
        ownerToken = resp.getToken();
        ownerUser = userRepository.findByPhone("+91" + phone).orElseThrow();
        primaryShop = shopRepository.findById(resp.getShopId()).orElseThrow();

        User sa = new User("sa_" + System.currentTimeMillis() + "@test.com", passwordEncoder.encode("Pass123!"), "Super Admin", "+9197" + (System.currentTimeMillis() % 100000000), UserRole.ROLE_SUPER_ADMIN);
        sa.setStatus("ACTIVE");
        sa.setActiveSessionId(java.util.UUID.randomUUID().toString());
        sa = userRepository.save(sa);
        superAdminToken = tokenProvider.generateTokenForUsername(sa.getEmail(), sa.getActiveSessionId());
    }

    @AfterEach
    void tearDown() {
        if (ownerUser != null) {
            cleanupOwnerAndStaff(ownerUser.getId(), ownerUser.getPhone());
        }
    }

    @Test
    @DisplayName("Case A: Staff Registration sets temporary PIN and requires first-time device enrollment")
    void testStaffRegistrationCaseA() throws Exception {
        String staffPhone = "9833344455";
        userRepository.findByPhone("+91" + staffPhone).ifPresent(u -> {
            try { authService.deleteAccount(u.getPhone()); } catch (Exception ignored) {}
        });

        CreateStaffRequest req = new CreateStaffRequest("Operator Alice", staffPhone, "654321", List.of(primaryShop.getId()));
        StaffSummaryResponse staffSummary = staffService.createStaff(ownerUser.getId(), req);

        assertNotNull(staffSummary.getId());
        assertEquals("AWAITING_ENROLLMENT", staffSummary.getDeviceEnrollmentStatus());
        assertTrue(staffSummary.getTemporaryPin());

        User staffUser = userRepository.findById(staffSummary.getId()).orElseThrow();
        assertTrue(staffUser.isTemporaryPin());

        // Routine PIN login is blocked before first-time device enrollment
        AuthRequest loginReq = new AuthRequest(staffPhone, "654321", "UserAgent");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("First-time device enrollment required")));
    }

    @Test
    @DisplayName("Case B: Registering existing staff under same owner assigns missing branches without charging seats or resetting PIN")
    void testStaffRegistrationCaseB() throws Exception {
        String staffPhone = "9844455566";
        userRepository.findByPhone("+91" + staffPhone).ifPresent(u -> {
            try { authService.deleteAccount(u.getPhone()); } catch (Exception ignored) {}
        });

        // Add 2nd branch
        CreateBranchRequest branchReq = new CreateBranchRequest(
                primaryShop.getBusiness().getId(),
                "Main Salon - East",
                "East Branch",
                "456 East Road",
                "9844455577",
                "Salon & Spa",
                15
        );
        BranchSummaryResponse branch2 = branchService.createBranch(ownerUser.getId(), branchReq);

        // First registration assigns primary branch
        CreateStaffRequest req1 = new CreateStaffRequest("Operator Bob", staffPhone, "112233", List.of(primaryShop.getId()));
        StaffSummaryResponse summary1 = staffService.createStaff(ownerUser.getId(), req1);
        assertEquals(1, summary1.getAssignedBranchIds().size());

        // Second registration for same staff with 2nd branch
        CreateStaffRequest req2 = new CreateStaffRequest("Operator Bob Updated", staffPhone, "999999", List.of(branch2.getId()));
        StaffSummaryResponse summary2 = staffService.createStaff(ownerUser.getId(), req2);

        // Must reuse same user ID
        assertEquals(summary1.getId(), summary2.getId());
        // Must contain both branches
        assertEquals(2, summary2.getAssignedBranchIds().size());
        assertTrue(summary2.getAssignedBranchIds().contains(primaryShop.getId()));
        assertTrue(summary2.getAssignedBranchIds().contains(branch2.getId()));

        // Temporary PIN must still be true
        assertTrue(summary2.getTemporaryPin());
    }

    @Test
    @DisplayName("Case C: Attempting to assign staff when already assigned to all branches returns informative error")
    void testStaffRegistrationCaseC() throws Exception {
        String staffPhone = "9855566677";
        CreateStaffRequest req = new CreateStaffRequest("Operator Charlie", staffPhone, "112233", List.of(primaryShop.getId()));
        staffService.createStaff(ownerUser.getId(), req);

        // Attempt duplicate assignment
        CreateStaffRequest dupReq = new CreateStaffRequest("Operator Charlie", staffPhone, "112233", List.of(primaryShop.getId()));
        Exception ex = assertThrows(IllegalArgumentException.class, () -> staffService.createStaff(ownerUser.getId(), dupReq));
        assertTrue(ex.getMessage().contains("already assigned to all selected branches"));
    }

    @Test
    @DisplayName("Case D: Attempting to register owner's own phone as staff is rejected")
    void testStaffRegistrationCaseD() {
        CreateStaffRequest req = new CreateStaffRequest("Owner Self", ownerUser.getPhone(), "112233", List.of(primaryShop.getId()));
        Exception ex = assertThrows(IllegalArgumentException.class, () -> staffService.createStaff(ownerUser.getId(), req));
        assertTrue(ex.getMessage().contains("owner's mobile number"));
    }

    @Test
    @DisplayName("Device Enrollment & Cryptographic Device-Bound Login Workflow")
    void testDeviceEnrollmentAndDeviceBoundLogin() throws Exception {
        String staffPhone = "9866677788";
        CreateStaffRequest staffReq = new CreateStaffRequest("Operator Dave", staffPhone, "654321", List.of(primaryShop.getId()));
        staffService.createStaff(ownerUser.getId(), staffReq);

        // Step 1: Enroll first device with temporary PIN and set permanent PIN
        KeyPair kp1 = generateTestEcKeyPair();
        String spkiBase64 = Base64.getEncoder().encodeToString(kp1.getPublic().getEncoded());
        String drid1 = "drid_device_1";

        DeviceEnrollmentRequest enrollReq = new DeviceEnrollmentRequest();
        enrollReq.setPhone(staffPhone);
        enrollReq.setTemporaryPin("654321");
        enrollReq.setNewPermanentPin("888999");
        enrollReq.setConfirmPermanentPin("888999");
        enrollReq.setDeviceRegistrationId(drid1);
        enrollReq.setDeviceName("Work Tablet");
        enrollReq.setPlatform("Android");
        enrollReq.setPublicKey(spkiBase64);

        mockMvc.perform(post("/api/auth/device-enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(enrollReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ROLE_STAFF"));

        User staffUser = userRepository.findByPhone("+91" + staffPhone).orElseThrow();
        assertFalse(staffUser.isTemporaryPin());
        assertTrue(deviceRegistrationRepository.existsByUserIdAndEnrollmentStatus(staffUser.getId(), "ACTIVE"));

        // Step 2: Normal login on enrolled device with valid cryptographic signature
        long timestamp = Instant.now().getEpochSecond();
        String payload = "+91" + staffPhone + ":" + timestamp + ":" + drid1;
        String signature = signPayload(kp1, payload);

        DeviceProofLoginRequest proofLogin = new DeviceProofLoginRequest(
                staffPhone,
                "888999",
                drid1,
                timestamp,
                signature
        );

        mockMvc.perform(post("/api/auth/device-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(proofLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // Step 3: Attempt login from another (unregistered) device -> MUST be rejected with DEVICE_MISMATCH
        String drid2 = "drid_unregistered_device";
        DeviceProofLoginRequest mismatchLogin = new DeviceProofLoginRequest(
                staffPhone,
                "888999",
                drid2,
                timestamp,
                null
        );

        mockMvc.perform(post("/api/auth/device-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mismatchLogin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Login blocked: This account is enrolled on another device")));

        // Step 4: Submit Device Replacement Request for device 2
        KeyPair kp2 = generateTestEcKeyPair();
        String spkiBase64Device2 = Base64.getEncoder().encodeToString(kp2.getPublic().getEncoded());

        DeviceReplacementSubmitRequest repReq = new DeviceReplacementSubmitRequest(
                staffPhone,
                "888999",
                "New Replacement Phone",
                "iOS",
                spkiBase64Device2
        );

        mockMvc.perform(post("/api/auth/device-replacement/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(repReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.newDeviceName").value("New Replacement Phone"));

        List<DeviceReplacementRequest> pending = replacementRequestRepository.findAllByUserIdOrderByRequestedAtDesc(staffUser.getId());
        assertFalse(pending.isEmpty());
        Long replacementId = pending.get(0).getId();

        // Step 5: Owner approves device replacement request
        mockMvc.perform(post("/api/owner/staff/device-replacements/" + replacementId + "/approve")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("approved")))
                .andExpect(jsonPath("$.request.status").value("APPROVED"));

        // Step 6: Verify old device is revoked and new device is now active
        DeviceRegistration activeDevice = deviceRegistrationRepository.findByUserIdAndEnrollmentStatus(staffUser.getId(), "ACTIVE").orElseThrow();
        assertEquals("New Replacement Phone", activeDevice.getDeviceName());

        // Now device 2 can log in successfully
        long ts2 = Instant.now().getEpochSecond();
        String payload2 = "+91" + staffPhone + ":" + ts2 + ":" + activeDevice.getDeviceRegistrationId();
        String sig2 = signPayload(kp2, payload2);

        DeviceProofLoginRequest newDeviceLogin = new DeviceProofLoginRequest(
                staffPhone,
                "888999",
                activeDevice.getDeviceRegistrationId(),
                ts2,
                sig2
        );

        mockMvc.perform(post("/api/auth/device-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newDeviceLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    @DisplayName("Bulk Branch Registration: Batch N branches with ₹99 each under single UTR and atomic admin approval")
    void testBulkBranchRegistrationAndAtomicApproval() throws Exception {
        // Create 2 additional branches
        CreateBranchRequest b1Req = new CreateBranchRequest(
                primaryShop.getBusiness().getId(),
                "Main Salon - Branch 2",
                "Branch 2",
                "Address 2",
                "9822233355",
                "Salon & Spa",
                15
        );
        BranchSummaryResponse b1 = branchService.createBranch(ownerUser.getId(), b1Req);

        CreateBranchRequest b2Req = new CreateBranchRequest(
                primaryShop.getBusiness().getId(),
                "Main Salon - Branch 3",
                "Branch 3",
                "Address 3",
                "9822233366",
                "Salon & Spa",
                15
        );
        BranchSummaryResponse b2 = branchService.createBranch(ownerUser.getId(), b2Req);

        assertEquals("PAYMENT_PENDING", b1.getRegistrationStatus());
        assertEquals("PAYMENT_PENDING", b2.getRegistrationStatus());

        // Submit bulk payment of ₹198 (2 * ₹99)
        BulkBranchPaymentRequest bulkReq = new BulkBranchPaymentRequest(
                List.of(b1.getId(), b2.getId()),
                "UTR_BULK_999888777",
                "absunny@ybl"
        );

        mockMvc.perform(post("/api/owner/branches/bulk-payment")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bulkReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.amount").value(198.0));

        PaymentRecord record = paymentRecordRepository.findByPaymentReference("UTR_BULK_999888777").orElseThrow();
        assertEquals(2, record.getBatchCount());
        assertEquals(new BigDecimal("198.00"), record.getAmount());

        // Admin atomic approval of bulk payment (strictly requires ROLE_SUPER_ADMIN)
        mockMvc.perform(post("/api/admin/payments/" + record.getId() + "/approve")
                        .header("Authorization", "Bearer " + superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("approved successfully")));

        // Verify both branches are atomically marked REGISTERED with registrationFeePaid = 99.00
        Shop branch1Db = shopRepository.findById(b1.getId()).orElseThrow();
        Shop branch2Db = shopRepository.findById(b2.getId()).orElseThrow();

        assertEquals("REGISTERED", branch1Db.getRegistrationStatus());
        assertEquals(new BigDecimal("99.00"), branch1Db.getRegistrationFeePaid());

        assertEquals("REGISTERED", branch2Db.getRegistrationStatus());
        assertEquals(new BigDecimal("99.00"), branch2Db.getRegistrationFeePaid());
    }
}
