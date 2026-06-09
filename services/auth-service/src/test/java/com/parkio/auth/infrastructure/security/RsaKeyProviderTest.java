package com.parkio.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class RsaKeyProviderTest {

    @Test
    void loadsEscapedPkcs8PemAndDerivesPublicKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(pair.getPrivate().getEncoded());
        String pem = ("-----BEGIN PRIVATE KEY-----\n"
                + encoded
                + "\n-----END PRIVATE KEY-----").replace("\n", "\\n");
        JwtProperties properties = new JwtProperties();
        properties.setPrivateKeyPem(pem);
        properties.setKeyId("pem-test-key");

        RsaKeyProvider provider = new RsaKeyProvider(properties);

        assertThat(provider.keyId()).isEqualTo("pem-test-key");
        assertThat(provider.publicKey().getModulus())
                .isEqualTo(((RSAPublicKey) pair.getPublic()).getModulus());
    }

    @Test
    void failsClosedWithoutPrivateKeyOrExplicitEphemeralFlag() {
        JwtProperties properties = new JwtProperties();

        assertThatThrownBy(() -> new RsaKeyProvider(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARKIO_JWT_PRIVATE_KEY_PEM");
    }
}
