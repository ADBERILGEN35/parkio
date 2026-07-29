package com.parkio.parking.trust;

import java.util.Objects;
import java.util.UUID;

/** Internal-only typed trust subject identity. */
public record TrustSubject(TrustSubjectType type, UUID subjectId) {

    public TrustSubject {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subjectId, "subjectId");
    }
}

