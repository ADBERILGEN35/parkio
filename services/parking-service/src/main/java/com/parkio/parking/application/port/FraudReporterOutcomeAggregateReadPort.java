package com.parkio.parking.application.port;

import com.parkio.parking.application.fraud.FraudReporterOutcomeAggregate;
import java.time.Instant;
import java.util.UUID;

/** Reads bounded reporter outcome aggregates for fraud feature construction. */
public interface FraudReporterOutcomeAggregateReadPort {

    FraudReporterOutcomeAggregate aggregateReporterContributions(
            UUID reporterUserId,
            Instant windowEndInclusive,
            Instant windowStartInclusive,
            UUID watermarkOutcomeRecordId);
}
