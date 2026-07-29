package com.parkio.parking.application.port;

import com.parkio.parking.application.fraud.ValidatedOutcomeForFraud;
import java.util.List;

/** Claims pending reporter outcomes for fraud shadow evaluation. */
public interface ValidatedOutcomeForFraudReadPort {

    List<ValidatedOutcomeForFraud> claimPendingReporterFraudCandidates(int limit);
}
