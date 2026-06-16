package com.parkio.auth.presentation;

import com.parkio.auth.infrastructure.security.RsaKeyProvider;
import com.parkio.auth.presentation.dto.JwkResponse;
import com.parkio.auth.presentation.dto.JwkSetResponse;
import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import com.parkio.auth.presentation.openapi.StandardApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "JWKS", description = "Public RS256 signing keys")
@StandardApiResponses
@RestController
@RequestMapping("/api/v1/auth/.well-known")
public class JwksController {

    private final RsaKeyProvider keys;

    public JwksController(RsaKeyProvider keys) {
        this.keys = keys;
    }

    @Operation(summary = "JSON Web Key Set for access-token verification")
    @GetMapping("/jwks.json")
    public JwkSetResponse jwks() {
        List<JwkResponse> jwks = new ArrayList<>();
        for (Map.Entry<String, RSAPublicKey> entry : keys.verificationKeys().entrySet()) {
            RSAPublicKey publicKey = entry.getValue();
            jwks.add(new JwkResponse(
                    "RSA",
                    entry.getKey(),
                    "sig",
                    "RS256",
                    base64Url(publicKey.getModulus()),
                    base64Url(publicKey.getPublicExponent())));
        }
        return new JwkSetResponse(jwks);
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Arrays.copyOfRange(bytes, offset, bytes.length));
    }
}
