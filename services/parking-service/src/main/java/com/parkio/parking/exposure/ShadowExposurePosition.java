package com.parkio.parking.exposure;

import java.util.Objects;

public record ShadowExposurePosition(int rank, ExposureCandidateId candidateId, ExposureEvaluation evaluation) {

    public ShadowExposurePosition {
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be >= 1");
        }
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(evaluation, "evaluation");
    }
}
