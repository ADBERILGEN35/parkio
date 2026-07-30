package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MunicipalSourceSlaPolicyTest {
    private static final Instant T0 = Instant.parse("2026-07-30T19:00:00Z");
    private static final MunicipalSourceSlaPolicy.Thresholds THRESHOLDS =
            MunicipalSourceSlaPolicy.Thresholds.defaults();

    @Test
    void consecutiveFailuresIgnoreSkippedAndResetOnSuccess() {
        List<MunicipalSourceSlaPolicy.CompletedRun> runs = List.of(
                failed("read_timeout", T0.plusSeconds(30)),
                skipped(T0.plusSeconds(20)),
                failed("read_timeout", T0.plusSeconds(10)),
                success(T0));
        assertThat(MunicipalSourceSlaPolicy.consecutiveFailures(runs)).isEqualTo(2);

        List<MunicipalSourceSlaPolicy.CompletedRun> afterRecovery = List.of(
                success(T0.plusSeconds(40)),
                failed("read_timeout", T0.plusSeconds(30)),
                failed("read_timeout", T0.plusSeconds(10)));
        assertThat(MunicipalSourceSlaPolicy.consecutiveFailures(afterRecovery)).isZero();
    }

    @Test
    void sustainedOutageReachesCritical() {
        List<MunicipalSourceSlaPolicy.CompletedRun> runs = List.of(
                failed("read_timeout", T0.plusSeconds(50)),
                failed("read_timeout", T0.plusSeconds(40)),
                failed("read_timeout", T0.plusSeconds(30)),
                failed("read_timeout", T0.plusSeconds(20)),
                failed("read_timeout", T0.plusSeconds(10)),
                success(T0));
        MunicipalSourceSlaPolicy.Evaluation evaluation = MunicipalSourceSlaPolicy.evaluate(
                true, true, true, runs, T0, 5, 0, T0.plusSeconds(60), THRESHOLDS);
        assertThat(evaluation.consecutiveFailures()).isEqualTo(5);
        assertThat(evaluation.operationalState()).isEqualTo(MunicipalSourceOperationalState.CRITICAL);
        assertThat(evaluation.lastFailureCategory()).isEqualTo("read_timeout");
    }

    @Test
    void transientFailureThenSuccessIsRecovering() {
        Instant successAt = T0.plusSeconds(120);
        List<MunicipalSourceSlaPolicy.CompletedRun> runs = List.of(
                success(successAt),
                failed("read_timeout", T0.plusSeconds(60)),
                success(T0));
        MunicipalSourceSlaPolicy.Evaluation evaluation = MunicipalSourceSlaPolicy.evaluate(
                true, true, true, runs, successAt, 1, 0, successAt.plusSeconds(30), THRESHOLDS);
        assertThat(evaluation.consecutiveFailures()).isZero();
        assertThat(evaluation.recovered()).isTrue();
        assertThat(evaluation.operationalState()).isEqualTo(MunicipalSourceOperationalState.RECOVERING);
    }

    @Test
    void disabledAndNeverRunStates() {
        assertThat(MunicipalSourceSlaPolicy.evaluate(
                        false, false, false, List.of(), null, 0, 0, T0, THRESHOLDS)
                .operationalState()).isEqualTo(MunicipalSourceOperationalState.DISABLED);
        assertThat(MunicipalSourceSlaPolicy.evaluate(
                        true, true, true, List.of(), null, 0, 0, T0, THRESHOLDS)
                .operationalState()).isEqualTo(MunicipalSourceOperationalState.NEVER_RUN);
    }

    @Test
    void staleRunningOverridesOtherStates() {
        MunicipalSourceSlaPolicy.Evaluation evaluation = MunicipalSourceSlaPolicy.evaluate(
                true, true, true, List.of(success(T0)), T0, 0, 1, T0.plusSeconds(10), THRESHOLDS);
        assertThat(evaluation.operationalState()).isEqualTo(MunicipalSourceOperationalState.STALE_OPERATION);
    }

    @Test
    void occupancyFreshnessRemainsSeparateFromSla() {
        assertThat(MunicipalSourceSlaPolicy.occupancyFreshness(T0, T0.plusSeconds(100), 300, 900))
                .isEqualTo(MunicipalOccupancyFreshness.LIVE);
        assertThat(MunicipalSourceSlaPolicy.occupancyFreshness(T0, T0.plusSeconds(400), 300, 900))
                .isEqualTo(MunicipalOccupancyFreshness.AGING);
        assertThat(MunicipalSourceSlaPolicy.occupancyFreshness(T0, T0.plusSeconds(1000), 300, 900))
                .isEqualTo(MunicipalOccupancyFreshness.STALE);
    }

    @Test
    void fixtureSequencesMatchIncidentMatrix() {
        // A healthy
        assertThat(MunicipalSourceSlaPolicy.consecutiveFailures(List.of(success(T0.plusSeconds(1)), success(T0))))
                .isZero();
        // B transient
        assertThat(MunicipalSourceSlaPolicy.consecutiveFailures(List.of(
                        success(T0.plusSeconds(2)), failed("read_timeout", T0.plusSeconds(1)), success(T0))))
                .isZero();
        // C sustained
        assertThat(MunicipalSourceSlaPolicy.consecutiveFailures(List.of(
                        failed("read_timeout", T0.plusSeconds(3)),
                        failed("read_timeout", T0.plusSeconds(2)),
                        failed("read_timeout", T0.plusSeconds(1)),
                        success(T0))))
                .isEqualTo(3);
        // D contract
        assertThat(MunicipalSourceSlaPolicy.consecutiveFailures(List.of(
                        failed("schema_contract", T0.plusSeconds(1)), success(T0))))
                .isEqualTo(1);
    }

    private static MunicipalSourceSlaPolicy.CompletedRun success(Instant at) {
        return new MunicipalSourceSlaPolicy.CompletedRun("SUCCESS", null, at, at);
    }

    private static MunicipalSourceSlaPolicy.CompletedRun failed(String category, Instant at) {
        return new MunicipalSourceSlaPolicy.CompletedRun("FAILED", category, at, at);
    }

    private static MunicipalSourceSlaPolicy.CompletedRun skipped(Instant at) {
        return new MunicipalSourceSlaPolicy.CompletedRun("SKIPPED", "concurrent_run", at, at);
    }
}
