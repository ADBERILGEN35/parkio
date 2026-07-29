package com.parkio.parking.application.port;

import com.parkio.parking.application.trust.ValidatedOutcomeForTrust;
import java.util.List;

/** Reads durable validated outcomes eligible for trust-shadow processing. */
public interface ValidatedOutcomeForTrustReadPort {

    List<ValidatedOutcomeForTrust> claimPendingReporterOutcomes(int limit);
}

