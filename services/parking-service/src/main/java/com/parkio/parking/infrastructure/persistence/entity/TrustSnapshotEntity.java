package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trust_snapshot")
public class TrustSnapshotEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subject_type", nullable = false, updatable = false, length = 64)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "trust_domain", nullable = false, length = 64)
    private String trustDomain;

    @Column(name = "trust_policy_version", nullable = false, length = 64)
    private String trustPolicyVersion;

    @Column(name = "snapshot_schema_version", nullable = false, length = 64)
    private String snapshotSchemaVersion;

    @Column(name = "last_evaluated_at", nullable = false)
    private Instant lastEvaluatedAt;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected TrustSnapshotEntity() {}

    public TrustSnapshotEntity(
            UUID id,
            String subjectType,
            UUID subjectId,
            String trustDomain,
            String trustPolicyVersion,
            String snapshotSchemaVersion,
            Instant lastEvaluatedAt,
            String snapshotJson,
            Instant createdAt,
            Instant updatedAt,
            Long version) {
        this.id = id;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.trustDomain = trustDomain;
        this.trustPolicyVersion = trustPolicyVersion;
        this.snapshotSchemaVersion = snapshotSchemaVersion;
        this.lastEvaluatedAt = lastEvaluatedAt;
        this.snapshotJson = snapshotJson;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public UUID getId() { return id; }
    public String getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public String getTrustDomain() { return trustDomain; }
    public String getTrustPolicyVersion() { return trustPolicyVersion; }
    public String getSnapshotSchemaVersion() { return snapshotSchemaVersion; }
    public Instant getLastEvaluatedAt() { return lastEvaluatedAt; }
    public String getSnapshotJson() { return snapshotJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}

