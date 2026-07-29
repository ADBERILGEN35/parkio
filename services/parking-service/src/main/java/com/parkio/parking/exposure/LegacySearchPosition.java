package com.parkio.parking.exposure;

import java.util.Objects;

public record LegacySearchPosition(int rank, ExposureCandidateId candidateId) {

    public LegacySearchPosition {
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be >= 1");
        }
        Objects.requireNonNull(candidateId, "candidateId");
    }
}
