package com.parkio.parking.application.fraud;

import com.parkio.parking.fraud.FraudAggregationVersion;
import com.parkio.parking.fraud.FraudDomain;
import com.parkio.parking.fraud.FraudFeatureVector;
import com.parkio.parking.fraud.FraudPolicyConfig;
import com.parkio.parking.fraud.FraudSubject;
import com.parkio.parking.fraud.FraudSubjectType;
import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import java.time.Instant;
import java.util.Objects;

/** Maps repository-backed aggregates to canonical fraud feature vectors. */
public final class ReporterFraudFeatureFactory {

    public static final String MAPPING_VERSION = "fraud-mapping-v1";

    private ReporterFraudFeatureFactory() {}

    public static FraudFeatureVector fromAggregate(FraudReporterOutcomeAggregate aggregate) {
        Objects.requireNonNull(aggregate, "aggregate");
        FraudSubject subject = new FraudSubject(FraudSubjectType.USER, aggregate.reporterUserId());
        return new FraudFeatureVector(
                subject,
                FraudDomain.CONTRIBUTION_INTEGRITY,
                aggregate.windowStart(),
                aggregate.windowEnd(),
                aggregate.watermarkOutcomeRecordId(),
                aggregate.watermarkEvaluatedAt(),
                aggregate.eligibleContributionCount(),
                aggregate.directConfirmedIncorrectCount(),
                aggregate.likelyIncorrectCount(),
                aggregate.confirmedCorrectCount(),
                aggregate.unknownCount(),
                aggregate.expiredWithoutEvidenceCount(),
                FraudAggregationVersion.V1);
    }

    public static Instant windowStartFor(OutcomeHistoryRecord trigger, Instant evaluatedAt) {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Instant rollingStart = evaluatedAt.minus(FraudPolicyConfig.ROLLING_WINDOW);
        return rollingStart.isBefore(Instant.EPOCH) ? Instant.EPOCH : rollingStart;
    }
}
