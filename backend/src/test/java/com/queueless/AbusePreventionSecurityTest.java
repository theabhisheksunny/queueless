package com.queueless;

import com.queueless.dto.PinLoginRequest;
import com.queueless.dto.PinResetRequest;
import com.queueless.dto.RegisterRequest;
import com.queueless.entity.TrialEligibilityLedger;
import com.queueless.exception.TrialAlreadyConsumedException;
import com.queueless.repository.TrialEligibilityLedgerRepository;
import com.queueless.repository.UserRepository;
import com.queueless.service.AuthService;
import com.queueless.service.PhoneNormalizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AbusePreventionSecurityTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private PhoneNormalizationService phoneNormalizationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TrialEligibilityLedgerRepository trialEligibilityLedgerRepository;

    private Clock mockClock;
    private Instant baseInstant;

    @BeforeEach
    void setUp() {
        baseInstant = Instant.parse("2026-11-15T16:00:00Z");
        mockClock = Clock.fixed(baseInstant, ZoneId.of("UTC"));
        authService.setClock(mockClock);
    }

    @Test
    @DisplayName("Anti-Enumeration: Non-existent mobile number in pinLogin returns generic error without leaking account existence")
    void testPinLoginDoesNotLeakAccountExistence() {
        String unregisteredPhone = "9855667703";

        BadCredentialsException ex = assertThrows(BadCredentialsException.class, () ->
                authService.pinLogin(new PinLoginRequest(unregisteredPhone, "123456", "test-device"), "127.0.0.1")
        );

        // Must NOT return "No account found with this mobile number"
        assertFalse(ex.getMessage().contains("No account found"),
                "Response must not leak that the account is missing from database!");
        assertEquals("Invalid mobile number or PIN.", ex.getMessage());
    }

    @Test
    @DisplayName("Trial Protection: PIN recovery cannot reset or modify trial eligibility ledger")
    void testPinRecoveryPreservesTrialLedger() {
        String phone = "9855667704";
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        // Register user directly with Mobile + PIN
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Trial User");
        req.setShopName("Trial Shop");
        req.setCategory("Salon & Spa");
        req.setPhone(phone);
        req.setPin("111222");
        req.setConfirmPin("111222");
        authService.register(req);

        TrialEligibilityLedger beforeLedger = trialEligibilityLedgerRepository.findByNormalizedPhone(normalizedPhone).orElseThrow();
        Instant trialStartedBefore = beforeLedger.getTrialStartedAt().atZone(ZoneId.of("UTC")).toInstant();

        // Advance 2 days
        Clock twoDaysLater = Clock.fixed(baseInstant.plusSeconds(172800), ZoneId.of("UTC"));
        authService.setClock(twoDaysLater);

        // Perform PIN reset with current PIN
        authService.resetPin(new PinResetRequest(phone, "111222", "333444", "333444", "new-device"));

        TrialEligibilityLedger afterLedger = trialEligibilityLedgerRepository.findByNormalizedPhone(normalizedPhone).orElseThrow();
        Instant trialStartedAfter = afterLedger.getTrialStartedAt().atZone(ZoneId.of("UTC")).toInstant();

        assertEquals(trialStartedBefore, trialStartedAfter, "PIN recovery must NEVER alter or reset trial eligibility start timestamp!");
    }

    @Test
    @DisplayName("Trial Protection: Account deletion and re-registration cannot bypass persistent trial ledger")
    void testAccountDeletionCannotBypassTrialLedger() {
        String phone = "9855667705";
        String normalizedPhone = phoneNormalizationService.normalize(phone);

        // Register user
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Delete Me User");
        req.setShopName("Delete Me Shop");
        req.setCategory("Clinic & Doctor");
        req.setPhone(phone);
        req.setPin("555666");
        req.setConfirmPin("555666");
        authService.register(req);

        // Delete account
        authService.deleteAccount(normalizedPhone);
        assertTrue(userRepository.findByPhone(normalizedPhone).isEmpty(), "User record must be deleted");

        // Verify trial ledger persists with status ACCOUNT_DELETED
        TrialEligibilityLedger ledger = trialEligibilityLedgerRepository.findByNormalizedPhone(normalizedPhone).orElseThrow();
        assertEquals("ACCOUNT_DELETED", ledger.getRegistrationStatus());

        // Attempt re-registration with same phone number
        Clock laterClock = Clock.fixed(baseInstant.plusSeconds(300), ZoneId.of("UTC"));
        authService.setClock(laterClock);

        RegisterRequest req2 = new RegisterRequest();
        req2.setFullName("Re-Registering User");
        req2.setShopName("New Shop");
        req2.setCategory("Clinic & Doctor");
        req2.setPhone(phone);
        req2.setPin("777888");
        req2.setConfirmPin("777888");

        assertThrows(TrialAlreadyConsumedException.class, () ->
                authService.register(req2),
                "Re-registering after account deletion must be blocked by the persistent trial ledger!"
        );
    }

    @Test
    @DisplayName("Duplicate Prevention: Second registration with same mobile number is rejected")
    void testDuplicateMobileNumberIsRejected() {
        String phone = "9855667706";
        RegisterRequest req1 = new RegisterRequest();
        req1.setFullName("Owner 1");
        req1.setShopName("Shop 1");
        req1.setCategory("Barber Shop");
        req1.setPhone(phone);
        req1.setPin("123456");
        req1.setConfirmPin("123456");
        authService.register(req1);

        RegisterRequest req2 = new RegisterRequest();
        req2.setFullName("Owner 2");
        req2.setShopName("Shop 2");
        req2.setCategory("Barber Shop");
        req2.setPhone(phone);
        req2.setPin("654321");
        req2.setConfirmPin("654321");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                authService.register(req2)
        );
        assertTrue(ex.getMessage().contains("already exists"), "Duplicate mobile registration must be rejected: " + ex.getMessage());
    }

    @Test
    @DisplayName("Validation: Invalid mobile number format is rejected")
    void testInvalidMobileNumberFormatRejected() {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Invalid Phone");
        req.setShopName("Invalid Shop");
        req.setPhone("12345"); // invalid
        req.setPin("123456");
        req.setConfirmPin("123456");

        assertThrows(IllegalArgumentException.class, () -> authService.register(req));
    }

    @Test
    @DisplayName("Validation: Mismatched confirm PIN is rejected")
    void testMismatchedConfirmPinRejected() {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Mismatched PIN");
        req.setShopName("Mismatched Shop");
        req.setPhone("9855667707");
        req.setPin("123456");
        req.setConfirmPin("654321"); // mismatched

        assertThrows(IllegalArgumentException.class, () -> authService.register(req));
    }
}
