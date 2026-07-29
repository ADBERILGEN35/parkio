package com.parkio.parking.application.port;

import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeEvaluation;
import java.time.Duration;

/** Low-cardinality outcome validation observability. */
public interface OutcomeObserverPort {

    void recordEvaluation(OutcomeEvaluation evaluation, Duration duration);

    static OutcomeObserverPort noop() {
        return (evaluation, duration) -> {};
    }

    static String classificationTag(OutcomeClassification classification) {
        return classification.name();
    }
}