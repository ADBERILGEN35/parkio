package com.parkio.parking.reward;

import java.util.Objects;
import java.util.UUID;

/** Stable internal subject that a pending reward intent is attributed to. */
public record RewardSubject(Type type, UUID subjectId) {

    public RewardSubject {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subjectId, "subjectId");
    }

    public enum Type {
        USER
    }
}
