package com.parkio.parking.calibration;

import java.util.Objects;

public record PolicyGovernanceDescriptor(
        CalibrationEngineType engineType,
        String policyVersion,
        PolicyLifecycleStatus lifecycleStatus,
        String authorityScope,
        String documentationRef) {

    public PolicyGovernanceDescriptor {
        Objects.requireNonNull(engineType, "engineType");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(lifecycleStatus, "lifecycleStatus");
        Objects.requireNonNull(authorityScope, "authorityScope");
        Objects.requireNonNull(documentationRef, "documentationRef");
    }
}