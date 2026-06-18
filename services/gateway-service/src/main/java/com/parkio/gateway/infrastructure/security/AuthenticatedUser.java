package com.parkio.gateway.infrastructure.security;

import java.util.List;

/**
 * Verified identity extracted from a valid access token. Mirrors the auth-service
 * JWT claims ({@code sub}, {@code email}, {@code roles}, {@code status},
 * {@code session_epoch}) and is the source for the trusted {@code X-User-*} headers
 * injected downstream.
 *
 * <p>{@code sessionEpoch} is {@code null} when the claim is absent (a legacy token
 * issued before epochs existed); the edge revocation check treats that as epoch 0.
 */
public record AuthenticatedUser(String userId, String email, List<String> roles, String status,
                                Long sessionEpoch) {
}
