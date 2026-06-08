package com.parkio.moderation.infrastructure.persistence.entity;

import com.parkio.moderation.domain.ModerationAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping for {@code moderation_decisions} (append-only audit). */
@Entity
@Table(name = "moderation_decisions")
public class ModerationDecisionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "moderator_id", nullable = false, updatable = false)
    private UUID moderatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false)
    private ModerationAction action;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ModerationDecisionEntity() {
        // for JPA
    }

    public ModerationDecisionEntity(UUID id, UUID caseId, UUID moderatorId, ModerationAction action,
                                    String note, Instant createdAt) {
        this.id = id;
        this.caseId = caseId;
        this.moderatorId = moderatorId;
        this.action = action;
        this.note = note;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getModeratorId() {
        return moderatorId;
    }

    public ModerationAction getAction() {
        return action;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
