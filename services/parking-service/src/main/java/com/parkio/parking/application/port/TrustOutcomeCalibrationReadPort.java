package com.parkio.parking.application.port;

import com.parkio.parking.application.calibration.TrustOutcomeCalibrationPair;
import java.util.List;

/** Read boundary for trust evaluations not yet linked to calibration observations. */
public interface TrustOutcomeCalibrationReadPort {

    List<TrustOutcomeCalibrationPair> findUnobservedTrustOutcomePairs(int limit);
}
