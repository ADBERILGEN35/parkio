package com.parkio.gateway.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class RemoteJwksKeyResolverTest {

    @Test
    void cachesKnownKeyAndRefreshesOnceForUnknownKid() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey publicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
        String body = """
                {"keys":[{"kty":"RSA","kid":"known","use":"sig","alg":"RS256","n":"%s","e":"%s"}]}
                """.formatted(base64Url(publicKey.getModulus()), base64Url(publicKey.getPublicExponent()));
        AtomicInteger calls = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        });

        JwtProperties properties = new JwtProperties();
        properties.setIssuer("parkio-auth");
        properties.setJwksUri("http://auth.test/jwks");
        properties.setJwksCacheTtl(Duration.ofMinutes(15));
        RemoteJwksKeyResolver resolver = new RemoteJwksKeyResolver(
                builder,
                properties,
                Clock.fixed(Instant.parse("2026-06-09T00:00:00Z"), ZoneOffset.UTC),
                "internal-test-secret");

        assertThat(resolver.resolve("known").block().getModulus()).isEqualTo(publicKey.getModulus());
        assertThat(resolver.resolve("known").block().getModulus()).isEqualTo(publicKey.getModulus());
        assertThat(calls).hasValue(1);

        assertThatThrownBy(() -> resolver.resolve("unknown").block())
                .isInstanceOf(JwtException.class);
        assertThat(calls).hasValue(2);
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(java.util.Arrays.copyOfRange(bytes, offset, bytes.length));
    }
}
