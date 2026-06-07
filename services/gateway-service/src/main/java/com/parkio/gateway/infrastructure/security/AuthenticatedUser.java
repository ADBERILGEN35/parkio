package com.parkio.gateway.infrastructure.security;

import java.util.List;

/**
 * Verified identity extracted from a valid access token. Mirrors the auth-service
 * JWT claims ({@code sub}, {@code email}, {@code roles}, {@code status}) and is the
 * source for the trusted {@code X-User-*} headers injected downstream.
 */
public record AuthenticatedUser(String userId, String email, List<String> roles, String status) {
}
