package com.parkio.gateway.infrastructure.security;

import java.util.List;

public record JwkSetResponse(List<JwkResponse> keys) {
}
