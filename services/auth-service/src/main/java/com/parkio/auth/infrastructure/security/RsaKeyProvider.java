package com.parkio.auth.infrastructure.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RsaKeyProvider {

    private final String keyId;
    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;
    /** Active kid -> public key, plus any previous (rotation) public verification keys. */
    private final Map<String, RSAPublicKey> verificationKeys;

    public RsaKeyProvider(JwtProperties properties) {
        this.keyId = properties.getKeyId();
        KeyPair pair = loadKeyPair(properties);
        this.privateKey = pair.getPrivate();
        this.publicKey = (RSAPublicKey) pair.getPublic();
        this.verificationKeys = buildVerificationKeys(
                this.keyId, this.publicKey, properties.getAdditionalPublicKeysJson());
    }

    public String keyId() {
        return keyId;
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    /**
     * All public keys a token may legitimately be verified against, keyed by {@code kid}:
     * the active signing key plus any previous keys still inside their rotation window.
     * Iteration order is active key first, then previous keys.
     */
    public Map<String, RSAPublicKey> verificationKeys() {
        return verificationKeys;
    }

    private static Map<String, RSAPublicKey> buildVerificationKeys(
            String activeKeyId, RSAPublicKey activeKey, String additionalJson) {
        Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
        keys.put(activeKeyId, activeKey);
        for (AdditionalPublicKey entry : parseAdditional(additionalJson)) {
            if (!StringUtils.hasText(entry.kid()) || !StringUtils.hasText(entry.pem())) {
                throw new IllegalStateException(
                        "parkio.security.jwt.additional-public-keys-json entries require non-blank kid and pem");
            }
            if (keys.containsKey(entry.kid())) {
                throw new IllegalStateException(
                        "Additional JWKS key id duplicates an existing key id: " + entry.kid());
            }
            keys.put(entry.kid(), fromPublicKeyPem(entry.pem()));
        }
        return Map.copyOf(keys);
    }

    private static List<AdditionalPublicKey> parseAdditional(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return new ObjectMapper().readValue(json, new TypeReference<List<AdditionalPublicKey>>() {});
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Invalid parkio.security.jwt.additional-public-keys-json "
                            + "(PARKIO_JWT_ADDITIONAL_PUBLIC_KEYS_JSON): expected a JSON array of {kid, pem}", ex);
        }
    }

    private static RSAPublicKey fromPublicKeyPem(String pem) {
        try {
            String normalized = pem.replace("\\n", "\n")
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] encoded = Base64.getDecoder().decode(normalized);
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException ex) {
            throw new IllegalStateException("Invalid additional RSA public key PEM (expected X.509/SPKI)", ex);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AdditionalPublicKey(String kid, String pem) {
    }

    private static KeyPair loadKeyPair(JwtProperties properties) {
        if (StringUtils.hasText(properties.getPrivateKeyPem())) {
            return fromPrivateKeyPem(properties.getPrivateKeyPem());
        }
        if (properties.isGenerateEphemeralKey()) {
            return generate();
        }
        throw new IllegalStateException(
                "parkio.security.jwt.private-key-pem (PARKIO_JWT_PRIVATE_KEY_PEM) must be configured");
    }

    private static KeyPair fromPrivateKeyPem(String pem) {
        try {
            String normalized = pem.replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] encoded = Base64.getDecoder().decode(normalized);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
            if (!(privateKey instanceof RSAPrivateCrtKey rsaPrivateKey)) {
                throw new IllegalStateException("JWT private key must be an RSA PKCS#8 CRT key");
            }
            RSAPublicKeySpec publicSpec = new RSAPublicKeySpec(
                    rsaPrivateKey.getModulus(), rsaPrivateKey.getPublicExponent());
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(publicSpec);
            return new KeyPair(publicKey, privateKey);
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new IllegalStateException("Invalid RSA private key PEM", ex);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to generate ephemeral RSA key", ex);
        }
    }
}
