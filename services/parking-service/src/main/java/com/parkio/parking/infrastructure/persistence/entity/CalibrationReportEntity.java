package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "calibration_report")
public class CalibrationReportEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Column(name = "engine_type", nullable = false, updatable = false, length = 64)
    private String engineType;

    @Column(name = "baseline_policy_version", updatable = false, length = 64)
    private String baselinePolicyVersion;

    @Column(name = "candidate_policy_version", updatable = false, length = 64)
    private String candidatePolicyVersion;

    @Column(name = "calibration_policy_version", nullable = false, updatable = false, length = 64)
    private String calibrationPolicyVersion;

    @Column(name = "window_start", nullable = false, updatable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false, updatable = false)
    private Instant windowEnd;

    @Column(name = "cohort_key", nullable = false, updatable = false, length = 256)
    private String cohortKey;

    @Column(name = "observation_count", nullable = false, updatable = false)
    private int observationCount;

    @Column(name = "labeled_count", nullable = false, updatable = false)
    private int labeledCount;

    @Column(name = "report_status", nullable = false, updatable = false, length = 64)
    private String reportStatus;

    @Column(name = "source_watermark", nullable = false, updatable = false)
    private Instant sourceWatermark;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "report_payload_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String reportPayloadJson;

    protected CalibrationReportEntity() {}

    public CalibrationReportEntity(
            UUID id,
            UUID reportId,
            String engineType,
            String baselinePolicyVersion,
            String candidatePolicyVersion,
            String calibrationPolicyVersion,
            Instant windowStart,
            Instant windowEnd,
            String cohortKey,
            int observationCount,
            int labeledCount,
            String reportStatus,
            Instant sourceWatermark,
            Instant generatedAt,
            Instant createdAt,
            String reportPayloadJson) {
        this.id = id;
        this.reportId = reportId;
        this.engineType = engineType;
        this.baselinePolicyVersion = baselinePolicyVersion;
        this.candidatePolicyVersion = candidatePolicyVersion;
        this.calibrationPolicyVersion = calibrationPolicyVersion;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.cohortKey = cohortKey;
        this.observationCount = observationCount;
        this.labeledCount = labeledCount;
        this.reportStatus = reportStatus;
        this.sourceWatermark = sourceWatermark;
        this.generatedAt = generatedAt;
        this.createdAt = createdAt;
        this.reportPayloadJson = reportPayloadJson;
    }

    public UUID getId() { return id; }
    public UUID getReportId() { return reportId; }
    public String getEngineType() { return engineType; }
    public String getBaselinePolicyVersion() { return baselinePolicyVersion; }
    public String getCandidatePolicyVersion() { return candidatePolicyVersion; }
    public String getCalibrationPolicyVersion() { return calibrationPolicyVersion; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public String getCohortKey() { return cohortKey; }
    public int getObservationCount() { return observationCount; }
    public int getLabeledCount() { return labeledCount; }
    public String getReportStatus() { return reportStatus; }
    public Instant getSourceWatermark() { return sourceWatermark; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getReportPayloadJson() { return reportPayloadJson; }
}