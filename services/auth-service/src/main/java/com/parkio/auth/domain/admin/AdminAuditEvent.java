package com.parkio.auth.domain.admin;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable audit record for an administrative action.
 */
public final class AdminAuditEvent {

    private final UUID id;
    private final Instant occurredAt;
    private final UUID actorUserId;
    private final String actorRoles;
    private final AdminAuditAction actionType;
    private final String targetResourceType;
    private final UUID targetResourceId;
    private final AdminAuditResult result;
    private final String reason;
    private final String correlationId;
    private final String traceId;
    private final String metadataJson;

    public AdminAuditEvent(UUID id,
                           Instant occurredAt,
                           UUID actorUserId,
                           String actorRoles,
                           AdminAuditAction actionType,
                           String targetResourceType,
                           UUID targetResourceId,
                           AdminAuditResult result,
                           String reason,
                           String correlationId,
                           String traceId,
                           String metadataJson) {
        this.id = Objects.requireNonNull(id, "id");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.actorUserId = Objects.requireNonNull(actorUserId, "actorUserId");
        this.actorRoles = Objects.requireNonNull(actorRoles, "actorRoles");
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.targetResourceType = Objects.requireNonNull(targetResourceType, "targetResourceType");
        this.targetResourceId = targetResourceId;
        this.result = Objects.requireNonNull(result, "result");
        this.reason = reason;
        this.correlationId = correlationId;
        this.traceId = traceId;
        this.metadataJson = metadataJson;
    }

    public UUID id() {
        return id;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public UUID actorUserId() {
        return actorUserId;
    }

    public String actorRoles() {
        return actorRoles;
    }

    public AdminAuditAction actionType() {
        return actionType;
    }

    public String targetResourceType() {
        return targetResourceType;
    }

    public UUID targetResourceId() {
        return targetResourceId;
    }

    public AdminAuditResult result() {
        return result;
    }

    public String reason() {
        return reason;
    }

    public String correlationId() {
        return correlationId;
    }

    public String traceId() {
        return traceId;
    }

    public String metadataJson() {
        return metadataJson;
    }
}
