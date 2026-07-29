package com.parkio.parking.outcome.port;

import com.parkio.parking.outcome.OutcomeEvaluation;

/**
 * Future Trust Engine (WP-05.11) extension point.
 *
 * <p>Outcome validation never mutates trust directly.
 */
public interface OutcomeTrustConsumerPort {

    void onValidatedOutcome(OutcomeEvaluation evaluation);

    static OutcomeTrustConsumerPort noop() {
        return evaluation -> {};
    }
}