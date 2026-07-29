package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "calibration_readiness_assessment")
public class CalibrationReadinessEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "assessment_id", nullable = false, updatable = false)
    private UUID assessmentId;

    @Column(name = "engine_type", nullable = false, updatable = false, length = 64)
    private String engineType;

    @Column(name = "policy_version", nullable = false, updatable = false, length = 64)
    private String policyVersion;

    @Column(name = "calibration_report_id", nullable = false, updatable = false)
    private UUID calibrationReportId;

    @Column(name = "readiness_status", nullable = false, updatable = false, length = 64)
    private String readinessStatus;

    @Column(name = "assessed_at", nullable = false, updatable = false)
    private Instant assessedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reason_payload_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String reasonPayloadJson;

    protected CalibrationReadinessEntity() {}

    public CalibrationReadinessEntity(
            UUID id,
            UUID assessmentId,
            String engineType,
            String policyVersion,
            UUID calibrationReportId,
            String readinessStatus,
            Instant assessedAt,
            Instant createdAt,
            String reasonPayloadJson) {
        this.id = id;
        this.assessmentId = assessmentId;
        this.engineType = engineType;
        this.policyVersion = policyVersion;
        this.calibrationReportId = calibrationReportId;
        this.readinessStatus = readinessStatus;
        this.assessedAt = assessedAt;
        this.createdAt = createdAt;
        this.reasonPayloadJson = reasonPayloadJson;
    }

    public UUID getId() { return id; }
    public UUID getAssessmentId() { return assessmentId; }
    public String getEngineType() { return engineType; }
    public String getPolicyVersion() { return policyVersion; }
    public UUID getCalibrationReportId() { return calibrationReportId; }
    public String getReadinessStatus() { return readinessStatus; }
    public Instant getAssessedAt() { return assessedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getReasonPayloadJson() { return reasonPayloadJson; }
}