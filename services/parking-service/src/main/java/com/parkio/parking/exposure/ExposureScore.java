package com.parkio.parking.exposure;

import java.util.Objects;
import java.util.Set;

public record ExposureScore(
        int total,
        Set<ExposureScoreComponent> components) {

    public ExposureScore {
        if (total < 0) {
            throw new IllegalArgumentException("total must be non-negative");
        }
        Objects.requireNonNull(components, "components");
        components = Set.copyOf(components);
    }
}
