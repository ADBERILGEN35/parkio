package com.parkio.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import io.jsonwebtoken.Jwts;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class Rs256JwtAndJwksTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void issuedTokenUsesRs256KidAndVerifiesWithJwksPublicKey() throws Exception {
        AuthUser user = new AuthUser(
                UUID.randomUUID(),
                "rider@parkio.test",
                "unused-hash",
                com.parkio.auth.domain.AuthUserStatus.ACTIVE,
                null,
                Set.of(new Role(UUID.randomUUID(), RoleName.USER)),
                Instant.parse("2026-06-09T00:00:00Z"),
                null);

        String token = jwtService.issue(user).token();
        JsonNode header = decodeSegment(token, 0);
        assertThat(header.path("alg").asText()).isEqualTo("RS256");
        assertThat(header.path("kid").asText()).isEqualTo("auth-test-key");

        String jwksJson = mockMvc.perform(get("/api/v1/auth/.well-known/jwks.json")
                        .header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").value("auth-test-key"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(jwksJson).doesNotContain("PRIVATE KEY");
        JsonNode jwk = objectMapper.readTree(jwksJson).path("keys").get(0);
        RSAPublicKey publicKey = publicKey(jwk.path("n").asText(), jwk.path("e").asText());
        var claims = Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer("parkio-auth-test")
                .requireAudience("parkio-api-test")
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo(user.id().toString());
        assertThat(claims.get("email", String.class)).isEqualTo(user.email());
        assertThat(claims.get("roles")).isEqualTo(java.util.List.of("USER"));
        assertThat(claims.get("status", String.class)).isEqualTo("ACTIVE");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getAudience()).containsExactly("parkio-api-test");
    }

    private JsonNode decodeSegment(String token, int index) throws Exception {
        String[] segments = token.split("\\.");
        return objectMapper.readTree(Base64.getUrlDecoder().decode(segments[index]));
    }

    private static RSAPublicKey publicKey(String modulus, String exponent) throws Exception {
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, decoder.decode(modulus)),
                new BigInteger(1, decoder.decode(exponent))));
    }
}
