package com.queueless.service.push;

import com.queueless.entity.ApplicationSetting;
import com.queueless.repository.ApplicationSettingRepository;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Optional;

@Component
public class VapidKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(VapidKeyProvider.class);

    public static final String SETTING_KEY_VAPID_PUBLIC = "app.webpush.vapid.public-key";
    public static final String SETTING_KEY_VAPID_PRIVATE = "app.webpush.vapid.private-key";

    private final ApplicationSettingRepository settingRepository;

    @Value("${app.webpush.vapid.public-key:}")
    private String configuredPublicKey;

    @Value("${app.webpush.vapid.private-key:}")
    private String configuredPrivateKey;

    @Value("${app.webpush.vapid.subject:mailto:support@queueless.in}")
    private String subject;

    private PublicKey publicKey;
    private PrivateKey privateKey;
    private String publicKeyBase64;

    public VapidKeyProvider(@Autowired(required = false) ApplicationSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @PostConstruct
    public void init() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        try {
            // 1. If explicit keys are configured via environment or properties, use them
            if (configuredPublicKey != null && !configuredPublicKey.isBlank() &&
                configuredPrivateKey != null && !configuredPrivateKey.isBlank()) {
                log.info("Initializing VAPID keys from configuration...");
                this.publicKey = Utils.loadPublicKey(configuredPublicKey.trim());
                this.privateKey = Utils.loadPrivateKey(configuredPrivateKey.trim());
                this.publicKeyBase64 = configuredPublicKey.trim();
            } else if (settingRepository != null) {
                // 2. Check persistent database for previously generated VAPID keys
                Optional<ApplicationSetting> dbPub = settingRepository.findBySettingKey(SETTING_KEY_VAPID_PUBLIC);
                Optional<ApplicationSetting> dbPriv = settingRepository.findBySettingKey(SETTING_KEY_VAPID_PRIVATE);

                if (dbPub.isPresent() && dbPriv.isPresent() &&
                    !dbPub.get().getSettingValue().isBlank() && !dbPriv.get().getSettingValue().isBlank()) {
                    log.info("Loaded persistent VAPID keys from application settings database.");
                    this.publicKeyBase64 = dbPub.get().getSettingValue().trim();
                    this.publicKey = Utils.loadPublicKey(this.publicKeyBase64);
                    this.privateKey = Utils.loadPrivateKey(dbPriv.get().getSettingValue().trim());
                } else {
                    // 3. Generate keys once, persist to database, and use
                    log.info("Generating and persisting standard VAPID P-256 KeyPair in database...");
                    KeyPair kp = generateEcKeyPair();
                    this.publicKey = kp.getPublic();
                    this.privateKey = kp.getPrivate();

                    byte[] pubBytes = Utils.encode((ECPublicKey) kp.getPublic());
                    byte[] privBytes = Utils.encode((ECPrivateKey) kp.getPrivate());

                    this.publicKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(pubBytes);
                    String privBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(privBytes);

                    settingRepository.save(new ApplicationSetting(SETTING_KEY_VAPID_PUBLIC, this.publicKeyBase64, "Web Push VAPID Public Key"));
                    settingRepository.save(new ApplicationSetting(SETTING_KEY_VAPID_PRIVATE, privBase64, "Web Push VAPID Private Key"));
                }
            } else {
                // Fallback for tests without settingRepository
                log.info("Generating ephemeral VAPID P-256 KeyPair for Web Push...");
                KeyPair kp = generateEcKeyPair();
                this.publicKey = kp.getPublic();
                this.privateKey = kp.getPrivate();
                byte[] pubBytes = Utils.encode((ECPublicKey) kp.getPublic());
                this.publicKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(pubBytes);
            }
            log.info("VAPID Web Push initialized. Subject: {}, Public Key: {}", subject, publicKeyBase64);
        } catch (Exception e) {
            log.error("Failed to initialize VAPID keys, generating fallback: {}", e.getMessage(), e);
            try {
                KeyPair kp = generateEcKeyPair();
                this.publicKey = kp.getPublic();
                this.privateKey = kp.getPrivate();
                byte[] pubBytes = Utils.encode((ECPublicKey) kp.getPublic());
                this.publicKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(pubBytes);
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot generate VAPID keys: " + ex.getMessage(), ex);
            }
        }
    }

    private KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }

    public String getSubject() {
        return subject;
    }

    public void setKeyPair(PublicKey pub, PrivateKey priv, String pubBase64) {
        this.publicKey = pub;
        this.privateKey = priv;
        this.publicKeyBase64 = pubBase64;
    }
}
