package com.parkio.parking.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.assessment.AssessmentCategory;
import com.parkio.parking.decision.assessment.AssessmentLevel;
import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.assessment.RiskAssessment;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceVector;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DecisionEngineGoldenFixtureTest {

    private final DecisionEngine engine = new DecisionEngine();

    @Test
    void a_strongNormal_fullPublishLowRisk() {
        DecisionResult result = engine.evaluate(DecisionGoldenFixtures.strongNormal(), DecisionGoldenFixtures.context());
        AssessmentBundle bundle = result.assessment().assessmentBundle().orElseThrow();
        RiskAssessment risk = result.assessment().riskAssessment().orElseThrow();

        assertThat(bundle.find(AssessmentCategory.CONTENT).orElseThrow().level())
                .isEqualTo(AssessmentLevel.POSITIVE);
        assertThat(bundle.find(AssessmentCategory.LEGALITY).orElseThrow().level())
                .isEqualTo(AssessmentLevel.ACCEPTABLE);
        assertThat(bundle.find(AssessmentCategory.LOCATION).orElseThrow().level())
                .isEqualTo(AssessmentLevel.POSITIVE);
        assertThat(bundle.find(AssessmentCategory.INTEGRITY).orElseThrow().level())
                .isEqualTo(AssessmentLevel.POSITIVE);
        assertThat(bundle.find(AssessmentCategory.TRUST)).isEmpty();
        assertThat(risk.hardConstraintActive()).isFalse();
        assertThat(risk.score()).isPresent();
        assertThat(risk.score().orElseThrow().value()).isLessThanOrEqualTo(ShadowDecisionPolicyConfig.RISK_FULL_PUBLISH_MAX);
        assertThat(result.disposition()).isEqualTo(PublicationDisposition.FULL_PUBLISH);
        assertThat(result.policyVersion()).isEqualTo("decision-shadow-v1");
        assertThat(result.reasonCodes()).contains(ReasonCode.of("DECISION_LOW_RISK_COMPLETE"));
    }

    @Test
    void b_conflictingLegality_preservedAndHold() {
        DecisionResult result =
                engine.evaluate(DecisionGoldenFixtures.conflictingLegality(), DecisionGoldenFixtures.context());
        AssessmentBundle bundle = result.assessment().assessmentBundle().orElseThrow();

        assertThat(bundle.find(AssessmentCategory.CONTENT).orElseThrow().level())
                .isIn(AssessmentLevel.POSITIVE, AssessmentLevel.ACCEPTABLE);
        assertThat(bundle.find(AssessmentCategory.LEGALITY).orElseThrow().level())
                .isEqualTo(AssessmentLevel.CONCERNING);
        assertThat(result.disposition()).isEqualTo(PublicationDisposition.HOLD);
        assertThat(result.reasonCodes())
                .anyMatch(r -> r.value().contains("LEGALITY") || r.value().contains("CONFLICT")
                        || r.value().contains("ELEVATED") || r.value().contains("HOLD"));
    }

    @Test
    void c_poorImageQuality_insufficientHoldNotRejected() {
        DecisionResult result =
                engine.evaluate(DecisionGoldenFixtures.poorImageQuality(), DecisionGoldenFixtures.context());
        AssessmentBundle bundle = result.assessment().assessmentBundle().orElseThrow();

        assertThat(bundle.find(AssessmentCategory.CONTENT).orElseThrow().level())
                .isEqualTo(AssessmentLevel.INSUFFICIENT_EVIDENCE);
        assertThat(result.disposition()).isEqualTo(PublicationDisposition.HOLD);
        assertThat(result.disposition()).isNotEqualTo(PublicationDisposition.REJECTED);
        assertThat(result.reasonCodes()).contains(ReasonCode.of("DECISION_INSUFFICIENT_EVIDENCE"));
    }

    @Test
    void d_aiFailed_notAutomaticRejected() {
        DecisionResult result =
                engine.evaluate(DecisionGoldenFixtures.aiFailedNoHardConstraint(), DecisionGoldenFixtures.context());
        AssessmentBundle bundle = result.assessment().assessmentBundle().orElseThrow();
        RiskAssessment risk = result.assessment().riskAssessment().orElseThrow();

        assertThat(bundle.find(AssessmentCategory.CONTENT).orElseThrow().level())
                .isEqualTo(AssessmentLevel.CONCERNING);
        assertThat(risk.hardConstraintActive()).isFalse();
        assertThat(result.disposition()).isNotEqualTo(PublicationDisposition.REJECTED);
        assertThat(result.disposition()).isEqualTo(PublicationDisposition.HOLD);
    }

    @Test
    void e_mediaMismatch_hardConstraintShadow() {
        DecisionResult result =
                engine.evaluate(DecisionGoldenFixtures.mediaMismatch(), DecisionGoldenFixtures.context());
        RiskAssessment risk = result.assessment().riskAssessment().orElseThrow();

        assertThat(risk.hardConstraintActive()).isTrue();
        assertThat(result.disposition()).isEqualTo(PublicationDisposition.SHADOW);
        assertThat(result.reasonCodes()).contains(ReasonCode.of("DECISION_HARD_MEDIA_MISMATCH"));
    }

    @Test
    void f_invalidCoordinates_hardConstraintHold() {
        DecisionResult result =
                engine.evaluate(DecisionGoldenFixtures.invalidCoordinates(), DecisionGoldenFixtures.context());
        AssessmentBundle bundle = result.assessment().assessmentBundle().orElseThrow();
        RiskAssessment risk = result.assessment().riskAssessment().orElseThrow();

        assertThat(bundle.find(AssessmentCategory.LOCATION).orElseThrow().hardConstraint()).isTrue();
        assertThat(risk.hardConstraintActive()).isTrue();
        assertThat(result.disposition()).isEqualTo(PublicationDisposition.HOLD);
        assertThat(result.reasonCodes()).contains(ReasonCode.of("DECISION_HARD_INVALID_COORDINATES"));
    }

    @Test
    void g_legalRiskCritical_explainableRestrictive() {
        DecisionResult result =
                engine.evaluate(DecisionGoldenFixtures.legalRiskCritical(), DecisionGoldenFixtures.context());
        AssessmentBundle bundle = result.assessment().assessmentBundle().orElseThrow();

        assertThat(bundle.find(AssessmentCategory.LEGALITY).orElseThrow().level())
                .isEqualTo(AssessmentLevel.CRITICAL);
        assertThat(result.disposition()).isIn(PublicationDisposition.HOLD, PublicationDisposition.REJECTED);
        assertThat(result.disposition()).isNotEqualTo(PublicationDisposition.FULL_PUBLISH);
        assertThat(bundle.find(AssessmentCategory.LEGALITY).orElseThrow().reasonCodes())
                .contains(ReasonCode.of("LEGALITY_CRITICAL_SCORE"));
    }

    @Test
    void h_staleEvent_notExpiredOrLocationInvalid() {
        DecisionResult result =
                engine.evaluate(DecisionGoldenFixtures.staleEvent(), DecisionGoldenFixtures.context());
        AssessmentBundle bundle = result.assessment().assessmentBundle().orElseThrow();

        assertThat(bundle.find(AssessmentCategory.INTEGRITY).orElseThrow().level())
                .isEqualTo(AssessmentLevel.UNCERTAIN);
        assertThat(bundle.find(AssessmentCategory.LOCATION).orElseThrow().level())
                .isNotEqualTo(AssessmentLevel.CRITICAL);
        assertThat(result.disposition()).isNotEqualTo(PublicationDisposition.EXPIRED);
    }

    @Test
    void i_missingTrustDeviceH3_noFabricatedAssessments() {
        AssessmentBundle bundle = engine.evaluateAssessments(
                DecisionGoldenFixtures.missingTrustDeviceH3(), DecisionGoldenFixtures.context());

        assertThat(bundle.find(AssessmentCategory.TRUST)).isEmpty();
        assertThat(bundle.find(AssessmentCategory.BEHAVIOR)).isEmpty();
        assertThat(bundle.find(AssessmentCategory.AVAILABILITY)).isEmpty();
        assertThat(bundle.assessments()).hasSize(4);
    }

    @Test
    void j_duplicateIdenticalEvidence_noDoubleCount() {
        DecisionResult once =
                engine.evaluate(DecisionGoldenFixtures.strongNormal(), DecisionGoldenFixtures.context());
        DecisionResult dup =
                engine.evaluate(DecisionGoldenFixtures.duplicateIdenticalEvidence(), DecisionGoldenFixtures.context());

        assertThat(dup.assessment().riskAssessment().orElseThrow().score())
                .isEqualTo(once.assessment().riskAssessment().orElseThrow().score());
        assertThat(dup.disposition()).isEqualTo(once.disposition());
    }

    @Test
    void k_conflictingEvidenceOrder_identicalOutput() {
        DecisionResult a =
                engine.evaluate(DecisionGoldenFixtures.conflictingOrderA(), DecisionGoldenFixtures.context());
        DecisionResult b =
                engine.evaluate(DecisionGoldenFixtures.conflictingOrderB(), DecisionGoldenFixtures.context());

        assertThat(a.disposition()).isEqualTo(b.disposition());
        assertThat(a.assessment().riskAssessment().orElseThrow().score())
                .isEqualTo(b.assessment().riskAssessment().orElseThrow().score());
        assertThat(a.assessment().assessmentBundle().orElseThrow().assessments())
                .isEqualTo(b.assessment().assessmentBundle().orElseThrow().assessments());
    }

    @Test
    void l_reservedCategoriesAbsent_notInsufficientUnlessRequired() {
        AssessmentBundle bundle = engine.evaluateAssessments(
                DecisionGoldenFixtures.strongNormal(), DecisionGoldenFixtures.context());
        assertThat(bundle.find(AssessmentCategory.TRUST)).isEmpty();
        assertThat(bundle.find(AssessmentCategory.BEHAVIOR)).isEmpty();
        assertThat(bundle.find(AssessmentCategory.AVAILABILITY)).isEmpty();
    }

    @Test
    void evaluationDoesNotSelectDisposition() {
        AssessmentBundle bundle = engine.evaluateAssessments(
                DecisionGoldenFixtures.strongNormal(), DecisionGoldenFixtures.context());
        // AssessmentBundle has no disposition field — evaluation layer is disposition-free.
        assertThat(bundle.parkingSpotId()).isEqualTo(DecisionGoldenFixtures.SPOT_ID);
    }

    @Test
    void unknownPolicyVersionRejected() {
        EvaluationContext bad = EvaluationContext.of(
                com.parkio.parking.decision.assessment.AssessmentVersion.of("unknown-v0"),
                DecisionGoldenFixtures.T0);
        assertThatThrownBy(() -> engine.evaluate(DecisionGoldenFixtures.strongNormal(), bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentRepeatedEvaluationIsDeterministic() throws Exception {
        EvidenceVector vector = DecisionGoldenFixtures.strongNormal();
        EvaluationContext context = DecisionGoldenFixtures.context();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<DecisionResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return engine.evaluate(vector, context);
                }));
            }
            start.countDown();
            DecisionResult first = futures.get(0).get(5, TimeUnit.SECONDS);
            for (Future<DecisionResult> future : futures) {
                DecisionResult next = future.get(5, TimeUnit.SECONDS);
                assertThat(next.disposition()).isEqualTo(first.disposition());
                assertThat(next.assessment().riskAssessment().orElseThrow().score())
                        .isEqualTo(first.assessment().riskAssessment().orElseThrow().score());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pureEngineLatencyBudgetForRepeatedInMemoryEvaluation() {
        EvidenceVector vector = DecisionGoldenFixtures.strongNormal();
        EvaluationContext context = DecisionGoldenFixtures.context();
        // Warmup
        for (int i = 0; i < 50; i++) {
            engine.evaluate(vector, context);
        }
        long start = System.nanoTime();
        int iterations = 500;
        for (int i = 0; i < iterations; i++) {
            engine.evaluate(vector, context);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        // Conservative local budget: 500 pure evaluations under 2s on CI hardware.
        assertThat(elapsedMs).as("elapsedMs=%s", elapsedMs).isLessThan(2000L);
    }
}
