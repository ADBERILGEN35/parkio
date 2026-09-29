package com.parkio.parking.application.recommendation.ranking.shadow;

import java.util.List;

/** Port for privacy-safe in-memory shadow evaluation retention. */
public interface ShadowEvaluationStore {

    void add(ShadowEvaluationRecord record);

    List<ShadowEvaluationRecord> snapshot();

    int size();

    void clear();
}
