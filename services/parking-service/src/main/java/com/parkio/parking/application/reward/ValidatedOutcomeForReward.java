package com.parkio.parking.application.reward;

import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import java.util.Objects;
import java.util.UUID;

/** Canonical repository-backed reward input resolved from durable outcome history. */
public record ValidatedOutcomeForReward(
        OutcomeHistoryRecord outcomeRecord,
        UUID reporterUserId) {

    public ValidatedOutcomeForReward {
        Objects.requireNonNull(outcomeRecord, "outcomeRecord");
        Objects.requireNonNull(reporterUserId, "reporterUserId");
    }
}
