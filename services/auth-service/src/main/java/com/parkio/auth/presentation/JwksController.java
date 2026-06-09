package com.parkio.auth.presentation;

import com.parkio.auth.infrastructure.security.RsaKeyProvider;
import com.parkio.auth.presentation.dto.JwkResponse;
import com.parkio.auth.presentation.dto.JwkSetResponse;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/.well-known")
public class JwksController {

    private final RsaKeyProvider keys;

    public JwksController(RsaKeyProvider keys) {
        this.keys = keys;
    }

    @GetMapping("/jwks.json")
    public JwkSetResponse jwks() {
        var publicKey = keys.publicKey();
        return new JwkSetResponse(List.of(new JwkResponse(
                "RSA",
                keys.keyId(),
                "sig",
                "RS256",
                base64Url(publicKey.getModulus()),
                base64Url(publicKey.getPublicExponent()))));
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        int offset = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Arrays.copyOfRange(bytes, offset, bytes.length));
    }
}
