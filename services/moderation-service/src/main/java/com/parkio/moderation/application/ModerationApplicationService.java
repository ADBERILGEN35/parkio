package com.parkio.moderation.application;

import com.parkio.moderation.application.command.CreateReportCommand;
import com.parkio.moderation.application.command.ResolveCaseCommand;
import com.parkio.moderation.application.event.AiValidationCompletedEvent;
import com.parkio.moderation.application.event.MediaRejectedEvent;
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
import com.parkio.moderation.domain.event.ParkingSpotRejectedByModeratorEvent;
import com.parkio.moderation.domain.event.UserRestoredEvent;
import com.parkio.moderation.domain.event.UserSuspendedEvent;
import com.parkio.moderation.domain.exception.ModerationErrorCode;
import com.parkio.moderation.domain.exception.ModerationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moderation use cases: user reports & appeals, moderator case review/resolution,
 * recording auditable decisions/violations, and idempotent reaction to upstream
 * events. Depends only on domain types and ports (ai-context/01).
 *
 * <p>moderation-service makes the final decisions (ai-context/02) but never mutates
 * other services' data (ai-context/03): penalties are emitted as events for parking,
 * user and gamification services to react to. Role checks happen in presentation.
 */
@Service
@Transactional
public class ModerationApplicationService {

    // --- Advisory AI decisioning (ai-context/02): names match ai-validation's enums,
    // carried as strings so unknown future values are tolerated. ---
    private static final String AI_STATUS_FAILED = "FAILED";
    private static final String AI_STATUS_WARNING = "WARNING";
    private static final String AI_RISK_NOT_A_PARKING_SPOT = "NOT_A_PARKING_SPOT";
    /** AiRiskType names that signal a legal/placement risk (mirrors AiRiskType.isLegalRisk). */
    private static final Set<String> LEGAL_RISK_TYPES = Set.of(
            "NO_PARKING_SIGN", "GARAGE_ENTRANCE", "BUS_STOP", "PEDESTRIAN_CROSSING",
            "FIRE_HYDRANT", "SIDEWALK", "TRAFFIC_FLOW_BLOCKING", "PRIVATE_PROPERTY");
    private static final int LEGAL_RISK_MEDIUM_AT = 50;
    private static final int LEGAL_RISK_HIGH_AT = 75;

    private final ModerationCaseRepository cases;
    private final UserReportRepository reports;
    private final AppealRepository appeals;
    private final UserViolationRepository violations;
    private final ModerationDecisionRepository decisions;
    private final OutboxEventAppender outbox;
    private final InboxEventRepository inbox;
    private final Clock clock;

    public ModerationApplicationService(ModerationCaseRepository cases,
                                        UserReportRepository reports,
                                        AppealRepository appeals,
                                        UserViolationRepository violations,
                                        ModerationDecisionRepository decisions,
                                        OutboxEventAppender outbox,
                                        InboxEventRepository inbox,
                                        Clock clock) {
        this.cases = cases;
        this.reports = reports;
        this.appeals = appeals;
        this.violations = violations;
        this.decisions = decisions;
        this.outbox = outbox;
        this.inbox = inbox;
        this.clock = clock;
    }

    // --- User-facing use cases ---

    /** Files a report. Serious reasons open (or feed) a moderation case immediately. */
    public UserReport createReport(CreateReportCommand command) {
        if (reports.existsByReporterAndTargetAndReason(command.reporterUserId(), command.targetType(),
                command.targetId(), command.reason())) {
            throw new ModerationException(ModerationErrorCode.DUPLICATE_REPORT,
                    "You have already reported this target for this reason.");
        }
        Instant now = clock.instant();
        UserReport report = UserReport.create(command.reporterUserId(), command.targetType(),
                command.targetId(), command.reason(), command.description(), now);

        if (command.reason().isSerious()) {
            // A report knows the reporter, not the target's owner — owner stays null.
            ModerationCase moderationCase = openOrReuseCase(command.targetType(), command.targetId(),
                    null, command.reason(), now);
            report.linkCase(moderationCase.id());
        }
        // Non-serious reports are recorded only; threshold-based opening is backlog.
        return reports.save(report);
    }

    public List<UserReport> getMyReports(UUID reporterUserId) {
        return reports.findByReporterUserId(reporterUserId);
    }

    /** Files an appeal — only for a RESOLVED case that targeted the appealing user. */
    public Appeal createAppeal(UUID appealUserId, UUID caseId, String note) {
        ModerationCase moderationCase = cases.findById(caseId)
                .filter(c -> c.targetsUser(appealUserId))
                .orElseThrow(() -> new ModerationException(ModerationErrorCode.CASE_NOT_FOUND));
        if (!moderationCase.isResolved()) {
            throw new ModerationException(ModerationErrorCode.CASE_NOT_RESOLVED,
                    "Only a resolved case can be appealed.");
        }
        if (appeals.existsByCaseIdAndAppealUserId(caseId, appealUserId)) {
            throw new ModerationException(ModerationErrorCode.DUPLICATE_APPEAL,
                    "You have already appealed this case.");
        }
        Instant now = clock.instant();
        Appeal appeal = appeals.save(Appeal.create(appealUserId, caseId, note, now));
        outbox.append(AppealCreatedEvent.of(appeal, now));
        return appeal;
    }

    // --- Moderator/admin use cases ---

    public List<ModerationCase> listCases(ModerationStatus status) {
        return status == null ? cases.findRecent() : cases.findByStatus(status);
    }

    public ModerationCase getCase(UUID caseId) {
        return requireCase(caseId);
    }

    public ModerationCase assignCase(UUID caseId, UUID moderatorId) {
        ModerationCase moderationCase = requireCase(caseId);
        moderationCase.assignTo(moderatorId, clock.instant());
        return cases.save(moderationCase);
    }

    /** Resolves a case, records the decision/violation, and emits the resulting events. */
    public ModerationCase resolveCase(ResolveCaseCommand command) {
        ModerationCase moderationCase = requireCase(command.caseId());
        Instant now = clock.instant();
        ModerationAction action = command.action();

        moderationCase.resolve(action, command.note(), now);
        cases.save(moderationCase);
        decisions.save(ModerationDecision.record(moderationCase.id(), command.moderatorId(), action, command.note(), now));
        outbox.append(ModerationCaseResolvedEvent.of(moderationCase, command.moderatorId(), now));

        if (moderationCase.targetType() == ModerationTargetType.PARKING_SPOT
                && (action == ModerationAction.REJECT || action == ModerationAction.MARK_RISKY)) {
            outbox.append(ParkingSpotRejectedByModeratorEvent.of(moderationCase.id(),
                    moderationCase.targetId(), moderationCase.ownerUserId(), command.moderatorId(),
                    moderationCase.reason().name(), now));
        }

        if (moderationCase.targetType() == ModerationTargetType.USER) {
            if (action == ModerationAction.SUSPEND_USER) {
                outbox.append(UserSuspendedEvent.of(moderationCase.id(), moderationCase.targetId(),
                        command.moderatorId(), now));
            } else if (action == ModerationAction.RESTORE_USER) {
                outbox.append(UserRestoredEvent.of(moderationCase.id(), moderationCase.targetId(),
                        command.moderatorId(), now));
            }
            if (action.isUserPenalty()) {
                violations.save(UserViolation.record(moderationCase.targetId(), moderationCase.id(),
                        moderationCase.reason(), moderationCase.severity(), action, now));
            }
        }
        return moderationCase;
    }

    public List<Appeal> listAppeals() {
        return appeals.findRecent();
    }

    /** Resolves an appeal; accepting a suspension appeal restores the user. */
    public Appeal resolveAppeal(UUID appealId, UUID moderatorId, boolean accepted, String note) {
        Appeal appeal = appeals.findById(appealId)
                .orElseThrow(() -> new ModerationException(ModerationErrorCode.APPEAL_NOT_FOUND));
        Instant now = clock.instant();
        appeal.resolve(accepted, moderatorId, note, now);
        appeals.save(appeal);
        outbox.append(AppealResolvedEvent.of(appeal, now));

        if (appeal.isAccepted()) {
            cases.findById(appeal.caseId())
                    .filter(c -> c.targetType() == ModerationTargetType.USER
                            && c.resolutionAction() == ModerationAction.SUSPEND_USER)
                    .ifPresent(c -> outbox.append(
                            UserRestoredEvent.of(c.id(), c.targetId(), moderatorId, now)));
        }
        return appeal;
    }

    // --- Event handlers (invoked directly for now; a Kafka consumer will call them) ---

    /** A community-rejected spot opens a case for moderator review/audit. */
    public void handleParkingSpotRejected(ParkingSpotRejectedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        // The community rejection carries the spot owner — record it so a later
        // moderator rejection can penalise/notify the owner.
        openCaseIfAbsent(ModerationTargetType.PARKING_SPOT, event.parkingSpotId(), event.ownerUserId(),
                ModerationReason.ILLEGAL_OR_RISKY);
        markProcessed(event.eventId(), "ParkingSpotRejected");
    }

    /** Only content-safety/relevance media rejections are moderation-worthy. */
    public void handleMediaRejected(MediaRejectedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        ModerationReason reason = switch (event.validationType() == null ? "" : event.validationType()) {
            case "IMAGE_SAFETY" -> ModerationReason.FAKE_PHOTO;
            case "PARKING_RELEVANCE" -> ModerationReason.NOT_A_PARKING_SPOT;
            default -> null;
        };
        if (reason != null) {
            openCaseIfAbsent(ModerationTargetType.MEDIA, event.mediaId(), null, reason);
        }
        markProcessed(event.eventId(), "MediaRejected");
    }

    /**
     * Advisory AI result (ai-context/02: AI never decides). A case is opened only when
     * the advisory signal is <em>meaningful</em>: a FAILED / not-a-parking-spot verdict,
     * or a WARNING carrying legal-placement risk. PASSED results — and WARNINGs that are
     * only image-quality concerns — open no case. The target is the parking spot when
     * known, otherwise the media object (a standalone media validation has no spot).
     */
    public void handleAiValidationCompleted(AiValidationCompletedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        aiValidationCase(event).ifPresent(decision -> {
            if (event.parkingSpotId() != null) {
                openCaseIfAbsent(ModerationTargetType.PARKING_SPOT, event.parkingSpotId(),
                        null, decision.reason(), decision.severity());
            } else {
                openCaseIfAbsent(ModerationTargetType.MEDIA, event.mediaId(),
                        null, decision.reason(), decision.severity());
            }
        });
        markProcessed(event.eventId(), "AiValidationCompleted");
    }

    // --- Internals ---

    private ModerationCase openOrReuseCase(ModerationTargetType targetType, UUID targetId, UUID ownerUserId,
                                           ModerationReason reason, Instant now) {
        return cases.findActiveByTarget(targetType, targetId)
                .map(existing -> {
                    existing.registerAdditionalReport(now);
                    return cases.save(existing);
                })
                .orElseGet(() -> openCase(targetType, targetId, ownerUserId, reason, reason.defaultSeverity(), now));
    }

    private void openCaseIfAbsent(ModerationTargetType targetType, UUID targetId, UUID ownerUserId,
                                  ModerationReason reason) {
        openCaseIfAbsent(targetType, targetId, ownerUserId, reason, reason.defaultSeverity());
    }

    private void openCaseIfAbsent(ModerationTargetType targetType, UUID targetId, UUID ownerUserId,
                                  ModerationReason reason, ModerationSeverity severity) {
        if (cases.findActiveByTarget(targetType, targetId).isEmpty()) {
            openCase(targetType, targetId, ownerUserId, reason, severity, clock.instant());
        }
    }

    private ModerationCase openCase(ModerationTargetType targetType, UUID targetId, UUID ownerUserId,
                                    ModerationReason reason, ModerationSeverity severity, Instant now) {
        ModerationCase moderationCase = cases.save(
                ModerationCase.open(targetType, targetId, ownerUserId, reason, severity, now));
        outbox.append(ModerationCaseOpenedEvent.of(moderationCase, now));
        return moderationCase;
    }

    /**
     * Maps an advisory AI result to the case it should open, if any. Driven by
     * {@code detectedRiskTypes} and {@code status} (ai-context/02). Returns empty for
     * PASSED results and for WARNINGs that carry no legal/placement risk (e.g. a pure
     * image-quality warning) — those are advisory only and don't warrant a case.
     */
    private Optional<AiCaseDecision> aiValidationCase(AiValidationCompletedEvent event) {
        List<String> risks = event.riskTypesOrEmpty();

        // FAILED, or an explicit "not a parking spot" signal → a serious case.
        if (AI_STATUS_FAILED.equals(event.status()) || risks.contains(AI_RISK_NOT_A_PARKING_SPOT)) {
            return Optional.of(new AiCaseDecision(ModerationReason.NOT_A_PARKING_SPOT, ModerationSeverity.HIGH));
        }

        // WARNING with legal/placement risk → a lower-severity case for review.
        if (AI_STATUS_WARNING.equals(event.status())) {
            boolean hasLegalRiskFlag = risks.stream().anyMatch(LEGAL_RISK_TYPES::contains);
            boolean highLegalRiskScore = event.legalRiskScore() >= LEGAL_RISK_MEDIUM_AT;
            if (hasLegalRiskFlag || highLegalRiskScore) {
                ModerationSeverity severity =
                        (hasLegalRiskFlag || highLegalRiskScore && event.legalRiskScore() >= LEGAL_RISK_HIGH_AT)
                                ? ModerationSeverity.MEDIUM : ModerationSeverity.LOW;
                return Optional.of(new AiCaseDecision(ModerationReason.ILLEGAL_OR_RISKY, severity));
            }
        }

        return Optional.empty();
    }

    private record AiCaseDecision(ModerationReason reason, ModerationSeverity severity) {
    }

    private ModerationCase requireCase(UUID caseId) {
        return cases.findById(caseId)
                .orElseThrow(() -> new ModerationException(ModerationErrorCode.CASE_NOT_FOUND));
    }

    private boolean alreadyProcessed(UUID eventId) {
        return inbox.existsByEventId(eventId);
    }

    private void markProcessed(UUID eventId, String eventType) {
        inbox.markProcessed(eventId, eventType, clock.instant());
    }
}
