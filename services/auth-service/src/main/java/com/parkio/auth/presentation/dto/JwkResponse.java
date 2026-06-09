package com.parkio.auth.presentation.dto;

public record JwkResponse(
        String kty,
        String kid,
        String use,
        String alg,
        String n,
        String e) {
}
