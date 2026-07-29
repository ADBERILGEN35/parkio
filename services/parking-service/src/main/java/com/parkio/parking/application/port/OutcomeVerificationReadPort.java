package com.parkio.parking.application.port;

import com.parkio.parking.outcome.normalization.OutcomeVerificationSignalData;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutcomeVerificationReadPort {

    List<OutcomeVerificationSignalData> findVerificationsForOutcome(UUID parkingSpotId, Instant cutoffInclusive);
}