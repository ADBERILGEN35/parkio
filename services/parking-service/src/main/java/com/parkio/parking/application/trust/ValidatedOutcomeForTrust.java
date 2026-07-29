package com.parkio.parking.application.trust;

import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import java.util.Objects;
import java.util.UUID;

/** Canonical repository-backed trust input resolved from durable outcome history. */
public record ValidatedOutcomeForTrust(
        OutcomeHistoryRecord outcomeRecord,
        UUID reporterUserId) {

    public ValidatedOutcomeForTrust {
        Objects.requireNonNull(outcomeRecord, "outcomeRecord");
        Objects.requireNonNull(reporterUserId, "reporterUserId");
    }
}

