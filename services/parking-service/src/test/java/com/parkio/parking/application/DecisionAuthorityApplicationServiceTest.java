package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.command.CreateSpotCommand;
import com.parkio.parking.application.port.DecisionAuthorityObserverPort;
import com.parkio.parking.application.port.MediaAccessPort;
import com.parkio.parking.application.port.MediaReadinessPort;
import com.parkio.parking.application.port.ModerationMetricsPort;
import com.parkio.parking.application.port.OutcomeEvaluationTriggerPort;
import com.parkio.parking.application.port.ExposureShadowObserverPort;
import com.parkio.parking.application.port.OutboxEventAppender;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.application.port.ParkingSpotSearchLogRepository;
import com.parkio.parking.application.port.ParkingSpotStatusHistoryRepository;
import com.parkio.parking.application.port.ParkingSpotVerificationRepository;
import com.parkio.parking.application.port.ParkingSpotViewLogRepository;
import com.parkio.parking.application.result.AiValidationApplyOutcome;
import com.parkio.parking.application.result.ControlledAuthorityApplyResult;
import com.parkio.parking.decision.application.EvidenceCollectionService;
import com.parkio.parking.decision.audit.DecisionAuditRecord;
import com.parkio.parking.decision.authority.AuthorityEligibilityReason;
import com.parkio.parking.decision.authority.DecisionExecutionMode;
import com.parkio.parking.decision.normalization.AiValidationEvidenceInput;
import com.parkio.parking.decision.policy.DecisionEngine;
import com.parkio.parking.decision.port.DecisionAuditPort;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ModerationPolicy;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.domain.event.ParkingEvent;
import com.parkio.parking.domain.event.ParkingSpotActivatedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DecisionAuthorityApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");
    private static final ModerationPolicy POLICY = new ModerationPolicy(
            Duration.ofMinutes(10),
            Duration.ofMinutes(2),
            Duration.ofMinutes(1),
            3,
            Duration.ofMinutes(15),
            Duration.ofMinutes(30));

    private FakeSpots spots;
    private FakeOutbox outbox;
    private ParkingApplicationService parking;
    private RecordingAudit audit;

    @BeforeEach
    void setUp() {
        spots = new FakeSpots();
        outbox = new FakeOutbox();
        audit = new RecordingAudit();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        parking = new ParkingApplicationService(
                spots,
                mock(ParkingSpotVerificationRepository.class),
                new FakeHistory(),
                mock(ParkingSpotViewLogRepository.class),
                mock(ParkingSpotSearchLogRepository.class),
                outbox,
                mock(MediaAccessPort.class),
                mock(MediaReadinessPort.class),
                new ParkingSearchSettings(1000, 10, 50000, 50),
                mock(ParkingSessionService.class),
                POLICY,
                mock(ModerationMetricsPort.class),
                OutcomeEvaluationTriggerPort.noop(),
                new ExposureShadowOrchestrator(
                        new ExposureShadowSettings(false, 0, 25),
                        mock(ExposureShadowApplicationService.class),
                        ExposureShadowObserverPort.NOOP),
                clock);
    }

    @Test
    void disabledUsesLegacyPathOnly() {
        DecisionAuthorityApplicationService authority = newService(DecisionAuthoritySettings.disabledDefaults());
        ParkingSpot spot = createPending();
        UUID eval = UUID.randomUUID();

        ControlledAuthorityApplyResult result = authority.applyAiValidation(
                spot.id(), "PASSED", List.of(), eval, NOW, strongAi(spot, eval));

        assertThat(result.authorityApplied()).isFalse();
        assertThat(result.eligibilityReason()).isEqualTo(AuthorityEligibilityReason.AUTHORITY_DISABLED);
        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(audit.records).isEmpty();
        assertThat(outbox.events).anyMatch(ParkingSpotActivatedEvent.class::isInstance);
    }

    @Test
    void zeroPercentCanaryUsesLegacyEvenWhenEnabled() {
        DecisionAuthorityApplicationService authority =
                newService(new DecisionAuthoritySettings(true, 0, "decision-shadow-v1"));
        ParkingSpot spot = createPending();
        UUID eval = UUID.randomUUID();

        ControlledAuthorityApplyResult result = authority.applyAiValidation(
                spot.id(), "PASSED", List.of(), eval, NOW, strongAi(spot, eval));

        assertThat(result.authorityApplied()).isFalse();
        assertThat(result.eligibilityReason()).isEqualTo(AuthorityEligibilityReason.ZERO_PERCENT_CANARY);
        assertThat(audit.records).isEmpty();
        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.ACTIVE);
    }

    @Test
    void selectedFullPublishAppliesAuthoritativelyWithAuditAndActivation() {
        DecisionAuthorityApplicationService authority =
                newService(new DecisionAuthoritySettings(true, 100, "decision-shadow-v1"));
        ParkingSpot spot = createPending();
        UUID eval = UUID.randomUUID();

        ControlledAuthorityApplyResult result = authority.applyAiValidation(
                spot.id(), "PASSED", List.of(), eval, NOW, strongAi(spot, eval));

        assertThat(result.authorityApplied()).isTrue();
        assertThat(result.applyOutcome().kind()).isEqualTo(AiValidationApplyOutcome.Kind.APPLIED);
        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(audit.records).hasSize(1);
        DecisionAuditRecord record = audit.records.get(0);
        assertThat(record.executionMode()).isEqualTo(DecisionExecutionMode.AUTHORITATIVE);
        assertThat(record.authorityApplied()).isTrue();
        assertThat(record.appliedStatus()).contains(ParkingSpotStatus.ACTIVE);
        assertThat(outbox.events.stream().filter(ParkingSpotActivatedEvent.class::isInstance).count())
                .isEqualTo(1);
    }

    @Test
    void auditFailurePreventsMutation() {
        DecisionAuthorityApplicationService authority =
                newService(new DecisionAuthoritySettings(true, 100, "decision-shadow-v1"));
        audit.failNext = true;
        ParkingSpot spot = createPending();
        UUID eval = UUID.randomUUID();

        assertThatThrownBy(() -> authority.applyAiValidation(
                        spot.id(), "PASSED", List.of(), eval, NOW, strongAi(spot, eval)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(spots.byId.get(spot.id()).status()).isEqualTo(ParkingSpotStatus.PENDING_VALIDATION);
        assertThat(outbox.events).noneMatch(ParkingSpotActivatedEvent.class::isInstance);
    }

    @Test
    void idempotentWhenAuthoritativeAuditAlreadyExists() {
        DecisionAuthorityApplicationService authority =
                newService(new DecisionAuthoritySettings(true, 100, "decision-shadow-v1"));
        ParkingSpot spot = createPending();
        UUID eval = UUID.randomUUID();
        authority.applyAiValidation(spot.id(), "PASSED", List.of(), eval, NOW, strongAi(spot, eval));
        long activations = outbox.events.stream()
                .filter(ParkingSpotActivatedEvent.class::isInstance)
                .count();

        ControlledAuthorityApplyResult second = authority.applyAiValidation(
                spot.id(), "PASSED", List.of(), eval, NOW, strongAi(spot, eval));

        assertThat(second.authorityApplied()).isTrue();
        assertThat(second.eligibilityReason())
                .isEqualTo(AuthorityEligibilityReason.IDEMPOTENT_ALREADY_APPLIED);
        assertThat(audit.records).hasSize(1);
        assertThat(outbox.events.stream().filter(ParkingSpotActivatedEvent.class::isInstance).count())
                .isEqualTo(activations);
    }

    @Test
    void mockedDisabledDoesNotCallAuthoritativePublish() {
        ParkingApplicationService parkingMock = mock(ParkingApplicationService.class);
        when(parkingMock.applyAiValidationResult(any(), any(), any(), any(), any()))
                .thenReturn(new AiValidationApplyOutcome(
                        ParkingSpotStatus.PENDING_VALIDATION,
                        ParkingSpotStatus.ACTIVE,
                        AiValidationApplyOutcome.Kind.APPLIED));
        ParkingSpot spot = createPending();
        DecisionAuthorityApplicationService svc = new DecisionAuthorityApplicationService(
                DecisionAuthoritySettings.disabledDefaults(),
                parkingMock,
                spots,
                new DecisionEngine(),
                new EvidenceCollectionService(),
                audit,
                DecisionAuthorityObserverPort.noop(),
                POLICY,
                Clock.fixed(NOW, ZoneOffset.UTC));

        svc.applyAiValidation(spot.id(), "PASSED", List.of(), UUID.randomUUID(), NOW, strongAi(spot, UUID.randomUUID()));

        verify(parkingMock).applyAiValidationResult(eq(spot.id()), eq("PASSED"), any(), any(), any());
        verify(parkingMock, never()).applyAuthoritativeFullPublish(any(), any(), any());
    }

    private DecisionAuthorityApplicationService newService(DecisionAuthoritySettings settings) {
        return new DecisionAuthorityApplicationService(
                settings,
                parking,
                spots,
                new DecisionEngine(),
                new EvidenceCollectionService(),
                audit,
                DecisionAuthorityObserverPort.noop(),
                POLICY,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ParkingSpot createPending() {
        return parking.createSpot(new CreateSpotCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                41.0082,
                28.9784,
                "Main St",
                "Nice",
                false,
                Set.of(VehicleType.SEDAN),
                ParkingContext.STREET_PARKING,
                LegalStatus.LEGAL,
                Set.of()));
    }

    private static AiValidationEvidenceInput strongAi(ParkingSpot spot, UUID eval) {
        return AiValidationEvidenceInput.of(
                eval,
                spot.mediaId(),
                spot.id(),
                "PASSED",
                List.of(),
                90,
                10,
                90,
                95,
                NOW);
    }

    private static final class FakeSpots implements ParkingSpotRepository {
        final Map<UUID, ParkingSpot> byId = new HashMap<>();

        @Override
        public ParkingSpot save(ParkingSpot spot) {
            byId.put(spot.id(), spot);
            return spot;
        }

        @Override
        public Optional<ParkingSpot> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<ParkingSpot> findByOwnerUserId(UUID ownerUserId) {
            return List.of();
        }

        @Override
        public List<ParkingSpot> findExpiredCandidates(Instant now, int batchSize) {
            return List.of();
        }

        @Override
        public List<ParkingSpot> findModerationTimeoutCandidates(Instant now, int batchSize) {
            return List.of();
        }

        @Override
        public List<ParkingSpot> findNearby(double latitude, double longitude, double radiusMeters, int limit) {
            return List.of();
        }
    }

    private static final class FakeHistory implements ParkingSpotStatusHistoryRepository {
        @Override
        public ParkingSpotStatusHistory save(ParkingSpotStatusHistory history) {
            return history;
        }
    }

    private static final class FakeOutbox implements OutboxEventAppender {
        final List<ParkingEvent> events = new ArrayList<>();

        @Override
        public void append(ParkingEvent event) {
            events.add(event);
        }
    }

    private static final class RecordingAudit implements DecisionAuditPort {
        final List<DecisionAuditRecord> records = new ArrayList<>();
        boolean failNext;

        @Override
        public void append(DecisionAuditRecord record) {
            if (failNext) {
                throw new IllegalStateException("audit write failed");
            }
            records.add(record);
        }

        @Override
        public Optional<DecisionAuditRecord> findById(UUID auditId) {
            return records.stream().filter(r -> r.auditId().equals(auditId)).findFirst();
        }

        @Override
        public List<DecisionAuditRecord> findByParkingSpotId(UUID parkingSpotId) {
            return records.stream().filter(r -> r.parkingSpotId().equals(parkingSpotId)).toList();
        }

        @Override
        public List<DecisionAuditRecord> findByEvaluationId(UUID evaluationId) {
            return records.stream().filter(r -> r.evaluationId().equals(evaluationId)).toList();
        }

        @Override
        public List<DecisionAuditRecord> findByPolicyVersion(String policyVersion) {
            return records.stream().filter(r -> r.policyVersion().equals(policyVersion)).toList();
        }

        @Override
        public List<DecisionAuditRecord> findByEvaluatedAtBetween(Instant fromInclusive, Instant toExclusive) {
            return List.of();
        }

        @Override
        public Optional<DecisionAuditRecord> findAuthoritativeApplied(UUID evaluationId, String policyVersion) {
            return records.stream()
                    .filter(r -> r.evaluationId().equals(evaluationId)
                            && r.policyVersion().equals(policyVersion)
                            && r.executionMode() == DecisionExecutionMode.AUTHORITATIVE
                            && r.authorityApplied())
                    .findFirst();
        }
    }
}