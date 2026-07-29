package com.parkio.parking.exposure;

import java.util.Objects;
import java.util.UUID;

/** Stable identity for an exposure candidate within a search result set. */
public record ExposureCandidateId(UUID value) {

    public ExposureCandidateId {
        Objects.requireNonNull(value, "value");
    }
}
