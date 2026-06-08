package com.parkio.moderation.infrastructure.persistence.entity;

import com.parkio.moderation.domain.AppealStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code appeals}. */
@Entity
@Table(name = "appeals")
public class AppealEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "appeal_user_id", nullable = false, updatable = false)
    private UUID appealUserId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "note", updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AppealStatus status;

    @Column(name = "resolver_moderator_id")
    private UUID resolverModeratorId;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected AppealEntity() {
        // for JPA
    }

    public AppealEntity(UUID id, UUID appealUserId, UUID caseId, String note, AppealStatus status,
                        UUID resolverModeratorId, String resolutionNote, Instant createdAt,
                        Instant resolvedAt, Long version) {
        this.id = id;
        this.appealUserId = appealUserId;
        this.caseId = caseId;
        this.note = note;
        this.status = status;
        this.resolverModeratorId = resolverModeratorId;
        this.resolutionNote = resolutionNote;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAppealUserId() {
        return appealUserId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getNote() {
        return note;
    }

    public AppealStatus getStatus() {
        return status;
    }

    public UUID getResolverModeratorId() {
        return resolverModeratorId;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Long getVersion() {
        return version;
    }
}
