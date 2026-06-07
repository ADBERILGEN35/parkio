package com.parkio.parking.presentation.dto;

import com.parkio.parking.domain.VerificationResult;
import jakarta.validation.constraints.NotNull;

/** Verify/report request: the outcome the verifying user observed. */
public record VerifySpotRequest(@NotNull VerificationResult result) {
}
