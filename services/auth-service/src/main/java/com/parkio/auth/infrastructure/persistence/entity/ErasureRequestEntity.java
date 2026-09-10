package com.parkio.auth.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "erasure_requests")
public class ErasureRequestEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "auth_user_id", nullable = false)
    private UUID authUserId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ErasureRequestEntity() {
    }

    public ErasureRequestEntity(UUID id, UUID authUserId, String status, Instant requestedAt) {
        this.id = id;
        this.authUserId = authUserId;
        this.status = status;
        this.requestedAt = requestedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAuthUserId() {
        return authUserId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void markInProgress() {
        this.status = "IN_PROGRESS";
        this.lastErrorCode = null;
    }

    public void markComplete(Instant completedAt) {
        this.status = "COMPLETE";
        this.completedAt = completedAt;
        this.lastErrorCode = null;
    }

    public void markFailedRetrying(String errorCode) {
        this.status = "FAILED_RETRYING";
        this.lastErrorCode = errorCode;
    }
}
