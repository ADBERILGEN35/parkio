package com.parkio.moderation.infrastructure.persistence.mapper;

import com.parkio.moderation.domain.Appeal;
import com.parkio.moderation.domain.ModerationCase;
import com.parkio.moderation.domain.ModerationDecision;
import com.parkio.moderation.domain.UserReport;
import com.parkio.moderation.domain.UserViolation;
import com.parkio.moderation.infrastructure.persistence.entity.AppealEntity;
import com.parkio.moderation.infrastructure.persistence.entity.ModerationCaseEntity;
import com.parkio.moderation.infrastructure.persistence.entity.ModerationDecisionEntity;
import com.parkio.moderation.infrastructure.persistence.entity.UserReportEntity;
import com.parkio.moderation.infrastructure.persistence.entity.UserViolationEntity;

/**
 * Maps between domain aggregates and JPA entities. Static, stateless: the persistence
 * layer never leaks into the domain and vice versa.
 */
public final class ModerationPersistenceMapper {

    private ModerationPersistenceMapper() {
    }

    public static ModerationCaseEntity toEntity(ModerationCase c) {
        return new ModerationCaseEntity(
                c.id(), c.targetType(), c.targetId(), c.reason(), c.severity(), c.status(),
                c.assignedModeratorId(), c.reportCount(), c.resolutionAction(), c.resolutionNote(),
                c.openedAt(), c.updatedAt(), c.resolvedAt(), c.version());
    }

    public static ModerationCase toDomain(ModerationCaseEntity e) {
        return new ModerationCase(
                e.getId(), e.getTargetType(), e.getTargetId(), e.getReason(), e.getSeverity(), e.getStatus(),
                e.getAssignedModeratorId(), e.getReportCount(), e.getResolutionAction(), e.getResolutionNote(),
                e.getOpenedAt(), e.getUpdatedAt(), e.getResolvedAt(), e.getVersion());
    }

    public static UserReportEntity toEntity(UserReport r) {
        return new UserReportEntity(
                r.id(), r.reporterUserId(), r.targetType(), r.targetId(), r.reason(),
                r.description(), r.caseId(), r.createdAt());
    }

    public static UserReport toDomain(UserReportEntity e) {
        return new UserReport(
                e.getId(), e.getReporterUserId(), e.getTargetType(), e.getTargetId(), e.getReason(),
                e.getDescription(), e.getCaseId(), e.getCreatedAt());
    }

    public static AppealEntity toEntity(Appeal a) {
        return new AppealEntity(
                a.id(), a.appealUserId(), a.caseId(), a.note(), a.status(),
                a.resolverModeratorId(), a.resolutionNote(), a.createdAt(), a.resolvedAt(), a.version());
    }

    public static Appeal toDomain(AppealEntity e) {
        return new Appeal(
                e.getId(), e.getAppealUserId(), e.getCaseId(), e.getNote(), e.getStatus(),
                e.getResolverModeratorId(), e.getResolutionNote(), e.getCreatedAt(), e.getResolvedAt(), e.getVersion());
    }

    public static UserViolationEntity toEntity(UserViolation v) {
        return new UserViolationEntity(
                v.id(), v.userId(), v.caseId(), v.reason(), v.severity(), v.action(), v.createdAt());
    }

    public static UserViolation toDomain(UserViolationEntity e) {
        return new UserViolation(
                e.getId(), e.getUserId(), e.getCaseId(), e.getReason(), e.getSeverity(), e.getAction(), e.getCreatedAt());
    }

    public static ModerationDecisionEntity toEntity(ModerationDecision d) {
        return new ModerationDecisionEntity(
                d.id(), d.caseId(), d.moderatorId(), d.action(), d.note(), d.createdAt());
    }

    public static ModerationDecision toDomain(ModerationDecisionEntity e) {
        return new ModerationDecision(
                e.getId(), e.getCaseId(), e.getModeratorId(), e.getAction(), e.getNote(), e.getCreatedAt());
    }
}
