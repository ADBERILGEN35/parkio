package com.parkio.parking.application.port;

import com.parkio.parking.application.calibration.FraudLedgerCalibrationCandidate;
import java.util.List;

/** Read boundary for fraud ledger evaluations not yet linked to calibration observations. */
public interface FraudLedgerCalibrationReadPort {

    List<FraudLedgerCalibrationCandidate> findUnobservedFraudEvaluations(int limit);
}
