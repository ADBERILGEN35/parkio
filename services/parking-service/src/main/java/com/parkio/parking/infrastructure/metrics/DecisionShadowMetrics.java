package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.port.DecisionShadowObserverPort;
import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.assessment.AssessmentCategory;
import com.parkio.parking.decision.assessment.AssessmentCompleteness;
import com.parkio.parking.decision.assessment.AssessmentLevel;
import com.parkio.parking.decision.calibration.AssessmentCategorySnapshot;
import com.parkio.parking.decision.calibration.DecisionCalibrationObservation;
import com.parkio.parking.decision.calibration.DecisivePolicyRule;
import com.parkio.parking.decision.calibration.EvidenceAvailabilityProfile;
import com.parkio.parking.decision.calibration.HardConstraintFamily;
import com.parkio.parking.decision.calibration.RiskBand;
import com.parkio.parking.decision.calibration.ShadowFailureStage;
import com.parkio.parking.decision.policy.ShadowDecisionPolicyConfig;
import com.parkio.parking.decision.shadow.LegacyPublicationOutcome;
import com.parkio.parking.decision.shadow.ShadowComparisonCategory;
import com.parkio.parking.domain.ParkingSpotStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Micrometer adapter for Decision Engine shadow observability.
 * Tags are bounded enums only - never spot/event/reason/risk values.
 */
@Component
public class DecisionShadowMetrics implements DecisionShadowObserverPort {

    private static final String POLICY_VERSION = ShadowDecisionPolicyConfig.POLICY_VERSION.value();
    private static final String POLICY_VERSION_TAG = "policy_version";

    private static final List<AssessmentCategory> ACTIVE_CATEGORIES = List.of(
            AssessmentCategory.CONTENT,
            AssessmentCategory.LEGALITY,
            AssessmentCategory.LOCATION,
            AssessmentCategory.INTEGRITY);

    private final Counter attempts;
    private final Counter successes;
    private final Timer duration;
    private final Timer engineDuration;
    private final Map<ShadowFailureStage, Counter> failureCounters;
    private final Map<PublicationDisposition, Counter> dispositionCounters;
    private final Map<ShadowComparisonCategory, Counter> comparisonCounters;
    private final Map<RiskBand, Counter> riskBandCounters;
    private final Map<HardConstraintFamily, Counter> hardConstraintFamilyCounters;
    private final Map<DecisivePolicyRule, Counter> decisiveRuleCounters;
    private final Map<EvidenceAvailabilityProfile, Counter> evidenceProfileCounters;
    private final Map<LegacyPublicationOutcome.Kind, Counter> legacyKindCounters;
    private final Map<ParkingSpotStatus, Counter> legacyStatusCounters;
    private final Map<AssessmentCategory, Map<AssessmentLevel, Counter>> assessmentLevelCounters;
    private final Map<AssessmentCategory, Map<AssessmentCompleteness, Counter>> assessmentCompletenessCounters;

    public DecisionShadowMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");

        this.attempts = Counter.builder("parkio.parking.decision.shadow.attempt")
                .description("Shadow Decision Engine evaluations attempted")
                .tag(POLICY_VERSION_TAG, POLICY_VERSION)
                .register(registry);
        this.successes = Counter.builder("parkio.parking.decision.shadow.success")
                .description("Shadow Decision Engine evaluations completed successfully")
                .tag(POLICY_VERSION_TAG, POLICY_VERSION)
                .register(registry);
        this.duration = Timer.builder("parkio.parking.decision.shadow.duration")
                .description("Shadow Decision Engine total orchestration duration")
                .tag(POLICY_VERSION_TAG, POLICY_VERSION)
                .register(registry);
        this.engineDuration = Timer.builder("parkio.parking.decision.shadow.engine.duration")
                .description("Shadow Decision Engine pure evaluation duration")
                .tag(POLICY_VERSION_TAG, POLICY_VERSION)
                .register(registry);

        this.failureCounters = enumCounters(
                registry,
                ShadowFailureStage.class,
                "parkio.parking.decision.shadow.failure",
                "Shadow Decision Engine evaluations that failed",
                "stage");
        this.dispositionCounters = enumCounters(
                registry,
                PublicationDisposition.class,
                "parkio.parking.decision.shadow.disposition",
                "Shadow disposition distribution",
                "disposition");
        this.comparisonCounters = enumCounters(
                registry,
                ShadowComparisonCategory.class,
                "parkio.parking.decision.shadow.comparison",
                "Legacy vs shadow comparison category",
                "category");
        this.riskBandCounters = enumCounters(
                registry,
                RiskBand.class,
                "parkio.parking.decision.shadow.risk_band",
                "Shadow risk band distribution",
                "band");
        this.hardConstraintFamilyCounters = enumCounters(
                registry,
                HardConstraintFamily.class,
                "parkio.parking.decision.shadow.hard_constraint_family",
                "Shadow hard-constraint family distribution",
                "family");
        this.decisiveRuleCounters = enumCounters(
                registry,
                DecisivePolicyRule.class,
                "parkio.parking.decision.shadow.decisive_rule",
                "Shadow decisive policy rule distribution",
                "rule");
        this.evidenceProfileCounters = enumCounters(
                registry,
                EvidenceAvailabilityProfile.class,
                "parkio.parking.decision.shadow.evidence_profile",
                "Shadow evidence availability profile distribution",
                "profile");
        this.legacyKindCounters = enumCounters(
                registry,
                LegacyPublicationOutcome.Kind.class,
                "parkio.parking.decision.shadow.legacy_kind",
                "Legacy publication outcome kind distribution",
                "kind");
        this.legacyStatusCounters = enumCounters(
                registry,
                ParkingSpotStatus.class,
                "parkio.parking.decision.shadow.legacy_status",
                "Legacy resulting ParkingSpotStatus distribution",
                "status");

        EnumMap<AssessmentCategory, Map<AssessmentLevel, Counter>> levels =
                new EnumMap<>(AssessmentCategory.class);
        EnumMap<AssessmentCategory, Map<AssessmentCompleteness, Counter>> completeness =
                new EnumMap<>(AssessmentCategory.class);
        for (AssessmentCategory category : ACTIVE_CATEGORIES) {
            EnumMap<AssessmentLevel, Counter> levelMap = new EnumMap<>(AssessmentLevel.class);
            for (AssessmentLevel level : AssessmentLevel.values()) {
                levelMap.put(
                        level,
                        Counter.builder("parkio.parking.decision.shadow.assessment_level")
                                .description("Shadow assessment level by active category")
                                .tag(POLICY_VERSION_TAG, POLICY_VERSION)
                                .tag("category", category.name())
                                .tag("level", level.name())
                                .register(registry));
            }
            levels.put(category, Map.copyOf(levelMap));

            EnumMap<AssessmentCompleteness, Counter> completenessMap =
                    new EnumMap<>(AssessmentCompleteness.class);
            for (AssessmentCompleteness value : AssessmentCompleteness.values()) {
                completenessMap.put(
                        value,
                        Counter.builder("parkio.parking.decision.shadow.assessment_completeness")
                                .description("Shadow assessment completeness by active category")
                                .tag(POLICY_VERSION_TAG, POLICY_VERSION)
                                .tag("category", category.name())
                                .tag("completeness", value.name())
                                .register(registry));
            }
            completeness.put(category, Map.copyOf(completenessMap));
        }
        this.assessmentLevelCounters = Map.copyOf(levels);
        this.assessmentCompletenessCounters = Map.copyOf(completeness);
    }

    @Override
    public void recordAttempt() {
        attempts.increment();
    }

    @Override
    public void recordSuccess(DecisionCalibrationObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (!POLICY_VERSION.equals(observation.policyVersion())) {
            throw new IllegalArgumentException(
                    "unknown policy_version: " + observation.policyVersion());
        }

        successes.increment();
        dispositionCounters.get(observation.shadowDisposition()).increment();
        comparisonCounters.get(observation.comparisonCategory()).increment();
        riskBandCounters.get(observation.riskBand()).increment();
        hardConstraintFamilyCounters.get(observation.hardConstraintFamily()).increment();
        decisiveRuleCounters.get(observation.decisiveRule()).increment();
        evidenceProfileCounters.get(observation.evidenceProfile()).increment();
        legacyKindCounters.get(observation.legacyKind()).increment();
        legacyStatusCounters.get(observation.legacyStatus()).increment();

        for (AssessmentCategorySnapshot snapshot : observation.assessments()) {
            AssessmentCategory category = snapshot.category();
            Map<AssessmentLevel, Counter> levelMap = assessmentLevelCounters.get(category);
            Map<AssessmentCompleteness, Counter> completenessMap =
                    assessmentCompletenessCounters.get(category);
            if (levelMap == null || completenessMap == null) {
                throw new IllegalArgumentException(
                        "assessment category not registered for metrics: " + category);
            }
            levelMap.get(snapshot.level()).increment();
            completenessMap.get(snapshot.completeness()).increment();
        }

        duration.record(observation.orchestrationDuration());
    }

    @Override
    public void recordFailure(ShadowFailureStage stage, Duration elapsed) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(elapsed, "elapsed");
        failureCounters.get(stage).increment();
        duration.record(elapsed);
    }

    /** Optional helper for pure Decision Engine evaluation timing. */
    public void recordEngineDuration(Duration elapsed) {
        Objects.requireNonNull(elapsed, "elapsed");
        engineDuration.record(elapsed);
    }

    private static <E extends Enum<E>> Map<E, Counter> enumCounters(
            MeterRegistry registry,
            Class<E> type,
            String name,
            String description,
            String tagKey) {
        EnumMap<E, Counter> counters = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            counters.put(
                    value,
                    Counter.builder(name)
                            .description(description)
                            .tag(POLICY_VERSION_TAG, POLICY_VERSION)
                            .tag(tagKey, value.name())
                            .register(registry));
        }
        return Map.copyOf(counters);
    }
}