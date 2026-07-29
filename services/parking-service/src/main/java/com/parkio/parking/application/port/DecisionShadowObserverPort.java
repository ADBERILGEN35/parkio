package com.parkio.parking.application.port;

import com.parkio.parking.decision.calibration.DecisionCalibrationObservation;
import com.parkio.parking.decision.calibration.ShadowFailureStage;
import java.time.Duration;

/**
 * Low-cardinality shadow Decision Engine observability.
 * Implementations must not tag ParkingSpot IDs, event IDs, reason codes, or exact risk scores.
 */
public interface DecisionShadowObserverPort {

    void recordAttempt();

    void recordSuccess(DecisionCalibrationObservation observation);

    void recordFailure(ShadowFailureStage stage, Duration duration);

    /** No-op observer when shadow metrics are unavailable. */
    static DecisionShadowObserverPort noop() {
        return new DecisionShadowObserverPort() {
            @Override
            public void recordAttempt() {}

            @Override
            public void recordSuccess(DecisionCalibrationObservation observation) {}

            @Override
            public void recordFailure(ShadowFailureStage stage, Duration duration) {}
        };
    }
}
