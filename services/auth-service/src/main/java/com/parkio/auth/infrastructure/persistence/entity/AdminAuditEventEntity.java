package com.parkio.auth.infrastructure.persistence.entity;

import com.parkio.auth.domain.admin.AdminAuditAction;
import com.parkio.auth.domain.admin.AdminAuditResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_audit_events")
public class AdminAuditEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "actor_roles", nullable = false, length = 256)
    private String actorRoles;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 64)
    private AdminAuditAction actionType;

    @Column(name = "target_resource_type", nullable = false, length = 64)
    private String targetResourceType;

    @Column(name = "target_resource_id")
    private UUID targetResourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16)
    private AdminAuditResult result;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    protected AdminAuditEventEntity() {
        // for JPA
    }

    public AdminAuditEventEntity(UUID id,
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
        this.id = id;
        this.occurredAt = occurredAt;
        this.actorUserId = actorUserId;
        this.actorRoles = actorRoles;
        this.actionType = actionType;
        this.targetResourceType = targetResourceType;
        this.targetResourceId = targetResourceId;
        this.result = result;
        this.reason = reason;
        this.correlationId = correlationId;
        this.traceId = traceId;
        this.metadataJson = metadataJson;
    }

    public UUID getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getActorRoles() {
        return actorRoles;
    }

    public AdminAuditAction getActionType() {
        return actionType;
    }

    public String getTargetResourceType() {
        return targetResourceType;
    }

    public UUID getTargetResourceId() {
        return targetResourceId;
    }

    public AdminAuditResult getResult() {
        return result;
    }

    public String getReason() {
        return reason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getMetadataJson() {
        return metadataJson;
    }
}
