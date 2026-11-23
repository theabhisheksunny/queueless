package com.queueless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.queueless.dto.*;
import com.queueless.entity.*;
import com.queueless.repository.*;
import com.queueless.service.BranchService;
import com.queueless.service.DeviceAuthService;
import com.queueless.service.StaffService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class DeviceAuthenticationSecurityAuditTest {

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
    private DeviceRegistrationRepository deviceRegistrationRepository;

    @Autowired
    private DeviceReplacementRequestRepository replacementRequestRepository;

    @Autowired
    private DeviceAuthChallengeRepository challengeRepository;

    @Autowired
    private TrialEligibilityLedgerRepository trialEligibilityLedgerRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private ShopMembershipRepository membershipRepository;

    @Autowired
    private DeviceAuthService deviceAuthService;

    @Autowired
    private StaffService staffService;

    @Autowired
    private BranchService branchService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.queueless.service.AuthService authService;

    private User owner1;
    private User owner2;
    private Shop primaryShop;

    private final String owner1Phone = "+919811122233";
    private final String owner2Phone = "+919844455566";
    private final String staffPhone = "+919877788899";

    @BeforeEach
    void setUp() throws Exception {
        cleanupData();

        RegisterRequest r1 = new RegisterRequest();
        r1.setPhone("9811122233");
        r1.setPin("123456");
        r1.setConfirmPin("123456");
        r1.setFullName("Owner One");
        r1.setShopName("Owner 1 Shop");
        r1.setCategory("Salon & Spa");
        r1.setAvgServiceDurationMinutes(15);
        AuthResponse res1 = authService.register(r1);
        owner1 = userRepository.findByPhone(owner1Phone).orElseThrow();
        primaryShop = shopRepository.findById(res1.getShopId()).orElseThrow();

        RegisterRequest r2 = new RegisterRequest();
        r2.setPhone("9844455566");
        r2.setPin("123456");
        r2.setConfirmPin("123456");
        r2.setFullName("Owner Two");
        r2.setShopName("Owner 2 Shop");
        r2.setCategory("Salon & Spa");
        r2.setAvgServiceDurationMinutes(15);
        authService.register(r2);
        owner2 = userRepository.findByPhone(owner2Phone).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        cleanupData();
    }

    private void cleanupData() {
        challengeRepository.deleteAll();
        replacementRequestRepository.deleteAll();
        deviceRegistrationRepository.deleteAll();
        membershipRepository.deleteAll();
        userSessionRepository.deleteAll();

        // 1. Delete all staff users first (foreign key owner_id -> users)
        userRepository.findByPhone(staffPhone).ifPresent(s -> {
            try {
                userSessionRepository.deleteAll(userSessionRepository.findAllByUserId(s.getId()));
                membershipRepository.deleteAll(membershipRepository.findAllByUserId(s.getId()));
                userRepository.delete(s);
            } catch (Exception ignored) {}
        });

        // 2. Delete owner accounts
        try {
            authService.deleteAccount(owner1Phone);
        } catch (Exception ignored) {}
        try {
            authService.deleteAccount(owner2Phone);
        } catch (Exception ignored) {}

        trialEligibilityLedgerRepository.deleteAll();
    }

    private KeyPair generateTestEcKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    private String signPayload(PrivateKey privateKey, String payload) throws Exception {
        Signature ecdsa = Signature.getInstance("SHA256withECDSA");
        ecdsa.initSign(privateKey);
        ecdsa.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ecdsa.sign());
    }

    @Test
    @DisplayName("Audit B: Server-issued cryptographic challenge prevents replay attacks atomically")
    void testChallengeResponseAndReplayPrevention() throws Exception {
        // 1. Create Staff under Owner 1
        CreateStaffRequest staffReq = new CreateStaffRequest("Ramesh Staff", staffPhone, "111222", List.of(primaryShop.getId()));
        StaffSummaryResponse staffSummary = staffService.createStaff(owner1.getId(), staffReq);
        assertEquals("AWAITING_ENROLLMENT", staffSummary.getDeviceEnrollmentStatus());

        // 2. Enroll Device A
        KeyPair devAKeys = generateTestEcKeyPair();
        String devAPublicKeyPem = Base64.getEncoder().encodeToString(devAKeys.getPublic().getEncoded());
        String devADrid = "drid_dev_a_phone";

        DeviceEnrollmentRequest enrollReq = new DeviceEnrollmentRequest(
                staffPhone,
                "111222",
                "333444",
                "333444",
                devADrid,
                "Device A",
                "Android",
                devAPublicKeyPem
        );

        mockMvc.perform(post("/api/auth/device-enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(enrollReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // 3. Request challenge for Device A
        DeviceChallengeRequest chReq = new DeviceChallengeRequest(staffPhone, devADrid);
        String chResJson = mockMvc.perform(post("/api/auth/device-challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        DeviceChallengeResponse chRes = objectMapper.readValue(chResJson, DeviceChallengeResponse.class);
        String nonce = chRes.getChallenge();
        assertNotNull(nonce);

        // 4. Sign challenge: {challenge}:{phone}:{deviceRegistrationId}
        String payload = nonce + ":" + staffPhone + ":" + devADrid;
        String signature = signPayload(devAKeys.getPrivate(), payload);

        DeviceProofLoginRequest loginReq = new DeviceProofLoginRequest(
                staffPhone,
                "333444",
                devADrid,
                nonce,
                signature
        );

        // First login with challenge succeeds
        mockMvc.perform(post("/api/auth/device-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // 5. REPLAY ATTACK: Attempting to reuse the exact same consumed challenge must fail!
        mockMvc.perform(post("/api/auth/device-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Replay rejected")));
    }

    @Test
    @DisplayName("Audit A & D: Unregistered device cannot request challenge or log in with mobile and PIN alone")
    void testUnregisteredDeviceBlocked() throws Exception {
        // Create & enroll staff on Device A
        CreateStaffRequest staffReq = new CreateStaffRequest("Ramesh Staff", staffPhone, "111222", List.of(primaryShop.getId()));
        staffService.createStaff(owner1.getId(), staffReq);

        KeyPair devAKeys = generateTestEcKeyPair();
        String devAPublicKeyPem = Base64.getEncoder().encodeToString(devAKeys.getPublic().getEncoded());
        String devADrid = "drid_dev_a_legit";

        DeviceEnrollmentRequest enrollReq = new DeviceEnrollmentRequest(
                staffPhone,
                "111222",
                "333444",
                "333444",
                devADrid,
                "Legit Phone",
                "Android",
                devAPublicKeyPem
        );
        mockMvc.perform(post("/api/auth/device-enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(enrollReq)))
                .andExpect(status().isOk());

        // Attempt from Device B (unregistered drid) to request challenge -> 403 Device Mismatch
        DeviceChallengeRequest fakeChReq = new DeviceChallengeRequest(staffPhone, "drid_attacker_device");
        mockMvc.perform(post("/api/auth/device-challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fakeChReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Login blocked")));

        // Attempt from Device B to login via pinLogin with correct PIN -> 403 Device Mismatch
        PinLoginRequest pinReq = new PinLoginRequest(staffPhone, "333444", "Attacker UA", "drid_attacker_device");
        mockMvc.perform(post("/api/auth/pin-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pinReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Login blocked")));
    }

    @Test
    @DisplayName("Audit C: Full replacement workflow with exact device ID & key binding, revocation, and enrollment completion")
    void testDeviceReplacementCompleteWorkflow() throws Exception {
        // 1. Create and enroll Device A
        CreateStaffRequest staffReq = new CreateStaffRequest("Ramesh Staff", staffPhone, "111222", List.of(primaryShop.getId()));
        staffService.createStaff(owner1.getId(), staffReq);

        KeyPair devAKeys = generateTestEcKeyPair();
        String devAPublicKeyPem = Base64.getEncoder().encodeToString(devAKeys.getPublic().getEncoded());
        String devADrid = "drid_device_a_old";

        DeviceEnrollmentRequest enrollReq = new DeviceEnrollmentRequest(
                staffPhone,
                "111222",
                "333444",
                "333444",
                devADrid,
                "Old Phone",
                "Android",
                devAPublicKeyPem
        );
        String devALoginJson = mockMvc.perform(post("/api/auth/device-enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(enrollReq)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        AuthResponse devAAuth = objectMapper.readValue(devALoginJson, AuthResponse.class);
        String devAToken = devAAuth.getToken();

        // 2. Device B submits replacement request
        KeyPair devBKeys = generateTestEcKeyPair();
        String devBPublicKeyPem = Base64.getEncoder().encodeToString(devBKeys.getPublic().getEncoded());
        String devBDrid = "drid_device_b_new";

        DeviceReplacementSubmitRequest repReq = new DeviceReplacementSubmitRequest(
                staffPhone,
                "333444",
                devBDrid,
                "New Tablet",
                "Android",
                devBPublicKeyPem
        );

        String repResJson = mockMvc.perform(post("/api/auth/device-replacement/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(repReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        DeviceReplacementResponse repRes = objectMapper.readValue(repResJson, DeviceReplacementResponse.class);
        Long repId = repRes.getId();

        // 3. Duplicate replacement submission is rejected while one is pending
        mockMvc.perform(post("/api/auth/device-replacement/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(repReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already pending")));

        // 4. Unauthorized Owner (Owner 2) cannot approve Owner 1's staff replacement
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> {
            deviceAuthService.approveDeviceReplacement(repId, owner2, "Unauthorized approval attempt");
        });

        // 5. Staff user cannot approve their own replacement
        User staffUser = userRepository.findByPhone(staffPhone).orElseThrow();
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> {
            deviceAuthService.approveDeviceReplacement(repId, staffUser, "Self approval attempt");
        });

        // 6. Authorized Owner (Owner 1) approves replacement
        deviceAuthService.approveDeviceReplacement(repId, owner1, "Approved replacement to new tablet");

        // 7. Verify Device A is revoked transactionally and old session invalidated
        DeviceRegistration devAReg = deviceRegistrationRepository.findByDeviceRegistrationId(devADrid).orElseThrow();
        assertEquals("REVOKED", devAReg.getEnrollmentStatus());

        // Device A old token is rejected on protected endpoint (unauthorized)
        mockMvc.perform(get("/api/owner/branches")
                        .header("Authorization", "Bearer " + devAToken))
                .andExpect(status().isUnauthorized());

        // 8. Device B completes replacement enrollment with exact bound key & deviceRegistrationId
        DeviceReplacementCompleteRequest compReq = new DeviceReplacementCompleteRequest(
                staffPhone,
                "333444",
                devBDrid,
                devBPublicKeyPem
        );

        mockMvc.perform(post("/api/auth/device-replacement/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(compReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // 9. Device B logs in successfully via challenge-response
        DeviceChallengeRequest chReqB = new DeviceChallengeRequest(staffPhone, devBDrid);
        String chResBJson = mockMvc.perform(post("/api/auth/device-challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chReqB)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        DeviceChallengeResponse chResB = objectMapper.readValue(chResBJson, DeviceChallengeResponse.class);
        String sigB = signPayload(devBKeys.getPrivate(), chResB.getChallenge() + ":" + staffPhone + ":" + devBDrid);

        DeviceProofLoginRequest devBLogin = new DeviceProofLoginRequest(
                staffPhone,
                "333444",
                devBDrid,
                chResB.getChallenge(),
                sigB
        );

        mockMvc.perform(post("/api/auth/device-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(devBLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        // 10. Device A cannot log in again -> 403 Device Mismatch
        DeviceChallengeRequest chReqA = new DeviceChallengeRequest(staffPhone, devADrid);
        mockMvc.perform(post("/api/auth/device-challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chReqA)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Audit D: Logout invalidates active session but preserves device enrollment")
    void testLogoutInvalidatesSessionPreservesDeviceEnrollment() throws Exception {
        // Create & enroll staff
        CreateStaffRequest staffReq = new CreateStaffRequest("Ramesh Staff", staffPhone, "111222", List.of(primaryShop.getId()));
        staffService.createStaff(owner1.getId(), staffReq);

        KeyPair devAKeys = generateTestEcKeyPair();
        String devAPublicKeyPem = Base64.getEncoder().encodeToString(devAKeys.getPublic().getEncoded());
        String devADrid = "drid_dev_a_logout_test";

        DeviceEnrollmentRequest enrollReq = new DeviceEnrollmentRequest(
                staffPhone,
                "111222",
                "333444",
                "333444",
                devADrid,
                "Phone",
                "Android",
                devAPublicKeyPem
        );
        String loginJson = mockMvc.perform(post("/api/auth/device-enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(enrollReq)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        AuthResponse authRes = objectMapper.readValue(loginJson, AuthResponse.class);
        String token = authRes.getToken();

        // Perform logout
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Device enrollment preserved")));

        // Calling protected endpoint with logged out token is rejected (unauthorized)
        mockMvc.perform(get("/api/owner/branches")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // Device registration remains ACTIVE
        DeviceRegistration reg = deviceRegistrationRepository.findByDeviceRegistrationId(devADrid).orElseThrow();
        assertEquals("ACTIVE", reg.getEnrollmentStatus());

        // Re-login from same enrolled device succeeds with new challenge
        DeviceChallengeRequest chReq = new DeviceChallengeRequest(staffPhone, devADrid);
        String chResJson = mockMvc.perform(post("/api/auth/device-challenge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chReq)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        DeviceChallengeResponse chRes = objectMapper.readValue(chResJson, DeviceChallengeResponse.class);
        String sig = signPayload(devAKeys.getPrivate(), chRes.getChallenge() + ":" + staffPhone + ":" + devADrid);

        DeviceProofLoginRequest reloginReq = new DeviceProofLoginRequest(
                staffPhone,
                "333444",
                devADrid,
                chRes.getChallenge(),
                sig
        );

        mockMvc.perform(post("/api/auth/device-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reloginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }
}
