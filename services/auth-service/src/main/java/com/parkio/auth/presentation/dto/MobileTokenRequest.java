package com.parkio.auth.presentation.dto;

/**
 * Optional request body for native mobile refresh / logout calls.
 *
 * <p>Web clients send no body and present the refresh token via the HttpOnly
 * cookie. Native clients (which have no reliable cookie jar) send the raw refresh
 * token here instead, alongside the {@code X-Parkio-Client: mobile} header. The
 * field is intentionally not {@code @NotBlank}: the controller validates presence
 * per transport so the web (cookie) path keeps a {@code null} body.
 */
public record MobileTokenRequest(String refreshToken) {
}
