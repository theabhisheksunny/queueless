package com.queueless.service.push;

import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Component
public class VapidKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(VapidKeyProvider.class);

    @Value("${app.webpush.vapid.public-key:}")
    private String configuredPublicKey;

    @Value("${app.webpush.vapid.private-key:}")
    private String configuredPrivateKey;

    @Value("${app.webpush.vapid.subject:mailto:support@queueless.in}")
    private String subject;

    private PublicKey publicKey;
    private PrivateKey privateKey;
    private String publicKeyBase64;

    @PostConstruct
    public void init() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        try {
            if (configuredPublicKey != null && !configuredPublicKey.isBlank() &&
                configuredPrivateKey != null && !configuredPrivateKey.isBlank()) {
                log.info("Initializing VAPID keys from configuration...");
                this.publicKey = Utils.loadPublicKey(configuredPublicKey.trim());
                this.privateKey = Utils.loadPrivateKey(configuredPrivateKey.trim());
                this.publicKeyBase64 = configuredPublicKey.trim();
            } else {
                log.info("Generating ephemeral VAPID P-256 KeyPair for Web Push...");
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
                kpg.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair kp = kpg.generateKeyPair();

                this.publicKey = kp.getPublic();
                this.privateKey = kp.getPrivate();

                byte[] pubBytes = Utils.encode((ECPublicKey) kp.getPublic());
                this.publicKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(pubBytes);
            }
            log.info("VAPID Web Push initialized. Subject: {}, Public Key: {}", subject, publicKeyBase64);
        } catch (Exception e) {
            log.error("Failed to initialize VAPID keys, generating fallback: {}", e.getMessage(), e);
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
                kpg.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair kp = kpg.generateKeyPair();
                this.publicKey = kp.getPublic();
                this.privateKey = kp.getPrivate();
                byte[] pubBytes = Utils.encode((ECPublicKey) kp.getPublic());
                this.publicKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(pubBytes);
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot generate VAPID keys: " + ex.getMessage(), ex);
            }
        }
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
