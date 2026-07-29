package com.parkio.parking.application.fraud;

import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import java.util.UUID;

/** Outcome that triggers a reporter fraud reevaluation candidate. */
public record ValidatedOutcomeForFraud(
        OutcomeHistoryRecord outcomeRecord,
        UUID reporterUserId) {
}
