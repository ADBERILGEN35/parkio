package com.parkio.auth.application.port;

import com.parkio.auth.application.result.IssuedAccessToken;
import com.parkio.auth.domain.AuthUser;

/**
 * Port for issuing signed JWT access tokens. The concrete implementation
 * (infrastructure) embeds the required claims: sub, email, roles, status,
 * iat and exp.
 */
public interface AccessTokenIssuer {

    IssuedAccessToken issue(AuthUser user);
}
