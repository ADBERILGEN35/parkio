package com.parkio.auth.presentation.dto;

import java.util.List;

public record JwkSetResponse(List<JwkResponse> keys) {
}
