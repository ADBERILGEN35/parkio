package com.parkio.parking.exposure;

import java.util.Objects;

public record ExposureScoreComponent(
        String name,
        int contribution) {

    public ExposureScoreComponent {
        Objects.requireNonNull(name, "name");
        if (contribution < 0) {
            throw new IllegalArgumentException("contribution must be non-negative");
        }
    }
}
