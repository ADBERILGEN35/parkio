package com.parkio.auth.presentation.dto.admin;

import jakarta.validation.constraints.NotBlank;

public record AdminReasonRequest(@NotBlank String reason) {
}
