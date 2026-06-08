package com.parkio.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.moderation.application.command.CreateReportCommand;
import com.parkio.moderation.application.command.ResolveCaseCommand;
import com.parkio.moderation.application.event.AiValidationCompletedEvent;
import com.parkio.moderation.application.event.ParkingSpotRejectedEvent;
import com.parkio.moderation.application.port.AppealRepository;
import com.parkio.moderation.application.port.InboxEventRepository;
import com.parkio.moderation.application.port.ModerationCaseRepository;
import com.parkio.moderation.application.port.ModerationDecisionRepository;
import com.parkio.moderation.application.port.OutboxEventAppender;
import com.parkio.moderation.application.port.UserReportRepository;
import com.parkio.moderation.application.port.UserViolationRepository;
import com.parkio.moderation.domain.Appeal;
import com.parkio.moderation.domain.ModerationAction;
import com.parkio.moderation.domain.ModerationCase;
import com.parkio.moderation.domain.ModerationDecision;
import com.parkio.moderation.domain.ModerationReason;
import com.parkio.moderation.domain.ModerationSeverity;
import com.parkio.moderation.domain.ModerationStatus;
import com.parkio.moderation.domain.ModerationTargetType;
import com.parkio.moderation.domain.UserReport;
import com.parkio.moderation.domain.UserViolation;
import com.parkio.moderation.domain.event.AppealCreatedEvent;
import com.parkio.moderation.domain.event.AppealResolvedEvent;
import com.parkio.moderation.domain.event.ModerationCaseOpenedEvent;
import com.parkio.moderation.domain.event.ModerationCaseResolvedEvent;
import com.parkio.moderation.domain.event.ModerationEvent;
import com.parkio.moderation.domain.event.UserRestoredEvent;
import com.parkio.moderation.domain.event.UserSuspendedEvent;
import com.parkio.moderation.domain.exception.ModerationErrorCode;
import com.parkio.moderation.domain.exception.ModerationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural unit tests for {@link ModerationApplicationService} using in-memory
 * fake ports — no Spring, no DB. Role enforcement is a presentation concern and is
 * covered separately in {@code ModerationControllerTest}.
 */
class ModerationApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-08T12:00:00Z");

    private FakeCaseRepository cases;
    private FakeReportRepository reports;
    private FakeAppealRepository appeals;
    private FakeViolationRepository violations;
    private FakeDecisionRepository decisions;
    private FakeInbox inbox;
    private FakeOutbox outbox;
    private ModerationApplicationService service;

    @BeforeEach
    void setUp() {
        cases = new FakeCaseRepository();
        reports = new FakeReportRepository();
        appeals = new FakeAppealRepository();
        violations = new FakeViolationRepository();
        decisions = new FakeDecisionRepository();
        inbox = new FakeInbox();
        outbox = new FakeOutbox();
        service = new ModerationApplicationService(cases, reports, appeals, violations, decisions,
                outbox, inbox, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void userCreatesNonSeriousReportWithoutOpeningCase() {
        UUID reporter = UUID.randomUUID();
        UUID spot = UUID.randomUUID();

        UserReport report = service.createReport(new CreateReportCommand(
                reporter, ModerationTargetType.PARKING_SPOT, spot, ModerationReason.OLD_PHOTO, "blurry"));

        assertThat(report.reporterUserId()).isEqualTo(reporter);
        assertThat(report.caseId()).isNull();
        assertThat(cases.byId).isEmpty();
    }

    @Test
    void seriousReportOpensCaseImmediately() {
        UUID reporter = UUID.randomUUID();
        UUID spot = UUID.randomUUID();

        UserReport report = service.createReport(new CreateReportCommand(
                reporter, ModerationTargetType.PARKING_SPOT, spot, ModerationReason.ILLEGAL_OR_RISKY, "danger"));

        assertThat(report.caseId()).isNotNull();
        assertThat(cases.byId).hasSize(1);
        ModerationCase opened = cases.byId.get(report.caseId());
        assertThat(opened.status()).isEqualTo(ModerationStatus.OPEN);
        assertThat(opened.reportCount()).isEqualTo(1);
        assertThat(outbox.events).hasExactlyElementsOfTypes(ModerationCaseOpenedEvent.class);
    }

    @Test
    void duplicateReportIsRejected() {
        UUID reporter = UUID.randomUUID();
        UUID spot = UUID.randomUUID();
        CreateReportCommand command = new CreateReportCommand(
                reporter, ModerationTargetType.PARKING_SPOT, spot, ModerationReason.WRONG_LOCATION, null);
        service.createReport(command);

        assertThatThrownBy(() -> service.createReport(command))
                .isInstanceOf(ModerationException.class)
                .extracting(e -> ((ModerationException) e).errorCode())
                .isEqualTo(ModerationErrorCode.DUPLICATE_REPORT);
    }

    @Test
    void secondSeriousReportForSameTargetFeedsTheSameCase() {
        UUID spot = UUID.randomUUID();
        service.createReport(new CreateReportCommand(UUID.randomUUID(), ModerationTargetType.PARKING_SPOT,
                spot, ModerationReason.ILLEGAL_OR_RISKY, null));
        service.createReport(new CreateReportCommand(UUID.randomUUID(), ModerationTargetType.PARKING_SPOT,
                spot, ModerationReason.ILLEGAL_OR_RISKY, null));

        assertThat(cases.byId).hasSize(1);
        assertThat(cases.byId.values().iterator().next().reportCount()).isEqualTo(2);
    }

    @Test
    void moderatorAssignsCase() {
        UUID caseId = openSeriousCase(ModerationTargetType.PARKING_SPOT, UUID.randomUUID());
        UUID moderator = UUID.randomUUID();

        ModerationCase assigned = service.assignCase(caseId, moderator);

        assertThat(assigned.status()).isEqualTo(ModerationStatus.IN_REVIEW);
        assertThat(assigned.assignedModeratorId()).isEqualTo(moderator);
    }

    @Test
    void moderatorResolvesCaseRecordingDecisionAndEvents() {
        UUID spot = UUID.randomUUID();
        UUID caseId = openSeriousCase(ModerationTargetType.PARKING_SPOT, spot);
        UUID moderator = UUID.randomUUID();
        outbox.events.clear();

        ModerationCase resolved = service.resolveCase(
                new ResolveCaseCommand(caseId, moderator, ModerationAction.REJECT, "confirmed"));

        assertThat(resolved.status()).isEqualTo(ModerationStatus.RESOLVED);
        assertThat(resolved.resolutionAction()).isEqualTo(ModerationAction.REJECT);
        assertThat(decisions.saved).hasSize(1);
        assertThat(outbox.events).hasExactlyElementsOfTypes(
                ModerationCaseResolvedEvent.class,
                com.parkio.moderation.domain.event.ParkingSpotRejectedByModeratorEvent.class);
    }

    @Test
    void moderatorRejectionEventCarriesOwnerWhenCaseOpenedFromCommunityRejection() {
        UUID spot = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        // A community rejection carries the owner — moderation records it on the case.
        service.handleParkingSpotRejected(new ParkingSpotRejectedEvent(
                UUID.randomUUID(), spot, owner, UUID.randomUUID(), "ILLEGAL_OR_RISKY", NOW));
        ModerationCase opened = cases.byId.values().iterator().next();
        outbox.events.clear();

        service.resolveCase(new ResolveCaseCommand(opened.id(), UUID.randomUUID(),
                ModerationAction.REJECT, "confirmed"));

        com.parkio.moderation.domain.event.ParkingSpotRejectedByModeratorEvent emitted = outbox.events.stream()
                .filter(e -> e instanceof com.parkio.moderation.domain.event.ParkingSpotRejectedByModeratorEvent)
                .map(e -> (com.parkio.moderation.domain.event.ParkingSpotRejectedByModeratorEvent) e)
                .findFirst().orElseThrow();
        assertThat(emitted.ownerUserId()).isEqualTo(owner);
        assertThat(emitted.parkingSpotId()).isEqualTo(spot);
        assertThat(emitted.reason()).isEqualTo("ILLEGAL_OR_RISKY");
    }

    @Test
    void suspendingUserRecordsViolationAndEmitsSuspendedEvent() {
        UUID targetUser = UUID.randomUUID();
        UUID caseId = openSeriousCase(ModerationTargetType.USER, targetUser);
        outbox.events.clear();

        service.resolveCase(new ResolveCaseCommand(caseId, UUID.randomUUID(), ModerationAction.SUSPEND_USER, "abuse"));

        assertThat(violations.saved).hasSize(1);
        assertThat(violations.saved.get(0).userId()).isEqualTo(targetUser);
        assertThat(outbox.events).hasExactlyElementsOfTypes(
                ModerationCaseResolvedEvent.class, UserSuspendedEvent.class);
    }

    @Test
    void userAppealsOwnResolvedCase() {
        UUID targetUser = UUID.randomUUID();
        UUID caseId = openSeriousCase(ModerationTargetType.USER, targetUser);
        service.resolveCase(new ResolveCaseCommand(caseId, UUID.randomUUID(), ModerationAction.SUSPEND_USER, "x"));
        outbox.events.clear();

        Appeal appeal = service.createAppeal(targetUser, caseId, "please reconsider");

        assertThat(appeal.appealUserId()).isEqualTo(targetUser);
        assertThat(appeals.byId).containsKey(appeal.id());
        assertThat(outbox.events).hasExactlyElementsOfTypes(AppealCreatedEvent.class);
    }

    @Test
    void userCannotAppealUnrelatedCase() {
        UUID targetUser = UUID.randomUUID();
        UUID caseId = openSeriousCase(ModerationTargetType.USER, targetUser);
        service.resolveCase(new ResolveCaseCommand(caseId, UUID.randomUUID(), ModerationAction.SUSPEND_USER, "x"));

        assertThatThrownBy(() -> service.createAppeal(UUID.randomUUID(), caseId, "not mine"))
                .isInstanceOf(ModerationException.class)
                .extracting(e -> ((ModerationException) e).errorCode())
                .isEqualTo(ModerationErrorCode.CASE_NOT_FOUND);
    }

    @Test
    void cannotAppealCaseThatIsNotResolved() {
        UUID targetUser = UUID.randomUUID();
        UUID caseId = openSeriousCase(ModerationTargetType.USER, targetUser);

        assertThatThrownBy(() -> service.createAppeal(targetUser, caseId, "early"))
                .isInstanceOf(ModerationException.class)
                .extracting(e -> ((ModerationException) e).errorCode())
                .isEqualTo(ModerationErrorCode.CASE_NOT_RESOLVED);
    }

    @Test
    void moderatorResolvesAppealAndAcceptingSuspensionRestoresUser() {
        UUID targetUser = UUID.randomUUID();
        UUID caseId = openSeriousCase(ModerationTargetType.USER, targetUser);
        service.resolveCase(new ResolveCaseCommand(caseId, UUID.randomUUID(), ModerationAction.SUSPEND_USER, "x"));
        Appeal appeal = service.createAppeal(targetUser, caseId, "please reconsider");
        outbox.events.clear();

        Appeal resolved = service.resolveAppeal(appeal.id(), UUID.randomUUID(), true, "fair point");

        assertThat(resolved.isAccepted()).isTrue();
        assertThat(outbox.events).hasExactlyElementsOfTypes(
                AppealResolvedEvent.class, UserRestoredEvent.class);
    }

    @Test
    void eventHandlerIsIdempotent() {
        UUID spot = UUID.randomUUID();
        ParkingSpotRejectedEvent event = new ParkingSpotRejectedEvent(
                UUID.randomUUID(), spot, UUID.randomUUID(), UUID.randomUUID(), "REJECTED", NOW);

        service.handleParkingSpotRejected(event);
        service.handleParkingSpotRejected(event); // redelivery

        assertThat(cases.byId).hasSize(1);
    }

    // --- AI validation (advisory) handling ---

    @Test
    void passedAiValidationOpensNoCase() {
        service.handleAiValidationCompleted(new AiValidationCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "PASSED", 80, 5, 90, 88, List.of(), NOW));

        assertThat(cases.byId).isEmpty();
        assertThat(outbox.events).isEmpty();
    }

    @Test
    void failedNotAParkingSpotWithSpotOpensHighParkingCase() {
        UUID spot = UUID.randomUUID();

        service.handleAiValidationCompleted(new AiValidationCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), spot,
                "FAILED", 0, 10, 20, 40, List.of("NOT_A_PARKING_SPOT"), NOW));

        assertThat(cases.byId).hasSize(1);
        ModerationCase opened = cases.byId.values().iterator().next();
        assertThat(opened.targetType()).isEqualTo(ModerationTargetType.PARKING_SPOT);
        assertThat(opened.targetId()).isEqualTo(spot);
        assertThat(opened.reason()).isEqualTo(ModerationReason.NOT_A_PARKING_SPOT);
        assertThat(opened.severity()).isEqualTo(ModerationSeverity.HIGH);
        assertThat(outbox.events).hasExactlyElementsOfTypes(ModerationCaseOpenedEvent.class);
    }

    @Test
    void failedNotAParkingSpotWithNullSpotOpensMediaCase() {
        UUID media = UUID.randomUUID();

        service.handleAiValidationCompleted(new AiValidationCompletedEvent(
                UUID.randomUUID(), media, null,
                "FAILED", 0, 10, 20, 40, List.of("NOT_A_PARKING_SPOT"), NOW));

        assertThat(cases.byId).hasSize(1);
        ModerationCase opened = cases.byId.values().iterator().next();
        assertThat(opened.targetType()).isEqualTo(ModerationTargetType.MEDIA);
        assertThat(opened.targetId()).isEqualTo(media);
        assertThat(opened.severity()).isEqualTo(ModerationSeverity.HIGH);
    }

    @Test
    void warningWithLegalRiskOpensRiskCase() {
        UUID spot = UUID.randomUUID();

        service.handleAiValidationCompleted(new AiValidationCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), spot,
                "WARNING", 70, 55, 80, 70, List.of("FIRE_HYDRANT"), NOW));

        assertThat(cases.byId).hasSize(1);
        ModerationCase opened = cases.byId.values().iterator().next();
        assertThat(opened.targetType()).isEqualTo(ModerationTargetType.PARKING_SPOT);
        assertThat(opened.reason()).isEqualTo(ModerationReason.ILLEGAL_OR_RISKY);
        assertThat(opened.severity()).isEqualTo(ModerationSeverity.MEDIUM);
    }

    @Test
    void warningWithoutLegalRiskOpensNoCase() {
        // A pure image-quality warning is advisory only — no legal/placement risk.
        service.handleAiValidationCompleted(new AiValidationCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "WARNING", 60, 10, 35, 60, List.of("LOW_IMAGE_QUALITY"), NOW));

        assertThat(cases.byId).isEmpty();
    }

    @Test
    void duplicateAiValidationEventIsSkippedViaInbox() {
        AiValidationCompletedEvent event = new AiValidationCompletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "FAILED", 0, 10, 20, 40, List.of("NOT_A_PARKING_SPOT"), NOW);

        service.handleAiValidationCompleted(event);
        service.handleAiValidationCompleted(event); // redelivery

        assertThat(cases.byId).hasSize(1);
    }

    // --- helpers -----------------------------------------------------------

    private UUID openSeriousCase(ModerationTargetType targetType, UUID targetId) {
        UserReport report = service.createReport(new CreateReportCommand(
                UUID.randomUUID(), targetType, targetId, ModerationReason.ABUSE_REPORT, "serious"));
        return report.caseId();
    }

    // --- fakes -------------------------------------------------------------

    private static final class FakeCaseRepository implements ModerationCaseRepository {
        private final Map<UUID, ModerationCase> byId = new HashMap<>();

        @Override
        public ModerationCase save(ModerationCase c) {
            byId.put(c.id(), c);
            return c;
        }

        @Override
        public Optional<ModerationCase> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<ModerationCase> findActiveByTarget(ModerationTargetType targetType, UUID targetId) {
            return byId.values().stream()
                    .filter(c -> c.targetType() == targetType && c.targetId().equals(targetId))
                    .filter(c -> c.status() == ModerationStatus.OPEN || c.status() == ModerationStatus.IN_REVIEW)
                    .findFirst();
        }

        @Override
        public List<ModerationCase> findRecent() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public List<ModerationCase> findByStatus(ModerationStatus status) {
            return byId.values().stream().filter(c -> c.status() == status).toList();
        }
    }

    private static final class FakeReportRepository implements UserReportRepository {
        private final Map<UUID, UserReport> byId = new HashMap<>();

        @Override
        public UserReport save(UserReport report) {
            byId.put(report.id(), report);
            return report;
        }

        @Override
        public boolean existsByReporterAndTargetAndReason(UUID reporterUserId, ModerationTargetType targetType,
                                                          UUID targetId, ModerationReason reason) {
            return byId.values().stream().anyMatch(r -> r.reporterUserId().equals(reporterUserId)
                    && r.targetType() == targetType && r.targetId().equals(targetId) && r.reason() == reason);
        }

        @Override
        public List<UserReport> findByReporterUserId(UUID reporterUserId) {
            return byId.values().stream().filter(r -> r.reporterUserId().equals(reporterUserId)).toList();
        }
    }

    private static final class FakeAppealRepository implements AppealRepository {
        private final Map<UUID, Appeal> byId = new HashMap<>();

        @Override
        public Appeal save(Appeal appeal) {
            byId.put(appeal.id(), appeal);
            return appeal;
        }

        @Override
        public Optional<Appeal> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public boolean existsByCaseIdAndAppealUserId(UUID caseId, UUID appealUserId) {
            return byId.values().stream()
                    .anyMatch(a -> a.caseId().equals(caseId) && a.appealUserId().equals(appealUserId));
        }

        @Override
        public List<Appeal> findRecent() {
            return new ArrayList<>(byId.values());
        }
    }

    private static final class FakeViolationRepository implements UserViolationRepository {
        private final List<UserViolation> saved = new ArrayList<>();

        @Override
        public UserViolation save(UserViolation violation) {
            saved.add(violation);
            return violation;
        }
    }

    private static final class FakeDecisionRepository implements ModerationDecisionRepository {
        private final List<ModerationDecision> saved = new ArrayList<>();

        @Override
        public ModerationDecision save(ModerationDecision decision) {
            saved.add(decision);
            return decision;
        }
    }

    private static final class FakeInbox implements InboxEventRepository {
        private final Set<UUID> processed = new HashSet<>();

        @Override
        public boolean existsByEventId(UUID eventId) {
            return processed.contains(eventId);
        }

        @Override
        public void markProcessed(UUID eventId, String eventType, Instant processedAt) {
            processed.add(eventId);
        }
    }

    private static final class FakeOutbox implements OutboxEventAppender {
        private final List<ModerationEvent> events = new ArrayList<>();

        @Override
        public void append(ModerationEvent event) {
            events.add(event);
        }
    }
}
