package com.parkio.parking.fraud;

import java.util.Objects;
import java.util.UUID;

/** Contextual fraud evaluation subject. */
public record FraudSubject(FraudSubjectType type, UUID subjectId) {

    public FraudSubject {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subjectId, "subjectId");
    }
}
