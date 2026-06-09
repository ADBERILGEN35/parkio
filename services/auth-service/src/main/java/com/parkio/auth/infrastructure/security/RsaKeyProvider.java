package com.parkio.auth.infrastructure.security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RsaKeyProvider {

    private final String keyId;
    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;

    public RsaKeyProvider(JwtProperties properties) {
        this.keyId = properties.getKeyId();
        KeyPair pair = loadKeyPair(properties);
        this.privateKey = pair.getPrivate();
        this.publicKey = (RSAPublicKey) pair.getPublic();
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
