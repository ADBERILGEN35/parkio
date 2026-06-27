package com.parkio.user.presentation.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record SmartReturnTodayRequest(@NotNull Instant expectedReturnAt) {
}
