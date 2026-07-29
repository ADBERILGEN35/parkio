package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "calibration_observation")
public class CalibrationObservationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "observation_id", nullable = false, updatable = false)
    private UUID observationId;

    @Column(name = "engine_type", nullable = false, updatable = false, length = 64)
    private String engineType;

    @Column(name = "source_evaluation_id", nullable = false, updatable = false)
    private UUID sourceEvaluationId;

    @Column(name = "label_source_id", nullable = false, updatable = false)
    private UUID labelSourceId;

    @Column(name = "policy_version", nullable = false, updatable = false, length = 64)
    private String policyVersion;

    @Column(name = "schema_version", nullable = false, updatable = false, length = 64)
    private String schemaVersion;

    @Column(name = "mapping_version", nullable = false, updatable = false, length = 64)
    private String mappingVersion;

    @Column(name = "aggregation_version", nullable = false, updatable = false, length = 64)
    private String aggregationVersion;

    @Column(name = "calibration_mapping_version", nullable = false, updatable = false, length = 64)
    private String calibrationMappingVersion;

    @Column(name = "calibration_policy_version", nullable = false, updatable = false, length = 64)
    private String calibrationPolicyVersion;

    @Column(name = "observation_horizon", nullable = false, updatable = false, length = 64)
    private String observationHorizon;

    @Column(name = "cohort_key", nullable = false, updatable = false, length = 256)
    private String cohortKey;

    @Column(name = "attribution_quality", nullable = false, updatable = false, length = 64)
    private String attributionQuality;

    @Column(name = "label_quality", nullable = false, updatable = false, length = 64)
    private String labelQuality;

    @Column(name = "label_finality", nullable = false, updatable = false, length = 64)
    private String labelFinality;

    @Column(name = "predicted_at", nullable = false, updatable = false)
    private Instant predictedAt;

    @Column(name = "labeled_at", nullable = false, updatable = false)
    private Instant labeledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "observation_payload_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String observationPayloadJson;

    protected CalibrationObservationEntity() {}

    public CalibrationObservationEntity(
            UUID id,
            UUID observationId,
            String engineType,
            UUID sourceEvaluationId,
            UUID labelSourceId,
            String policyVersion,
            String schemaVersion,
            String mappingVersion,
            String aggregationVersion,
            String calibrationMappingVersion,
            String calibrationPolicyVersion,
            String observationHorizon,
            String cohortKey,
            String attributionQuality,
            String labelQuality,
            String labelFinality,
            Instant predictedAt,
            Instant labeledAt,
            Instant createdAt,
            String observationPayloadJson) {
        this.id = id;
        this.observationId = observationId;
        this.engineType = engineType;
        this.sourceEvaluationId = sourceEvaluationId;
        this.labelSourceId = labelSourceId;
        this.policyVersion = policyVersion;
        this.schemaVersion = schemaVersion;
        this.mappingVersion = mappingVersion;
        this.aggregationVersion = aggregationVersion;
        this.calibrationMappingVersion = calibrationMappingVersion;
        this.calibrationPolicyVersion = calibrationPolicyVersion;
        this.observationHorizon = observationHorizon;
        this.cohortKey = cohortKey;
        this.attributionQuality = attributionQuality;
        this.labelQuality = labelQuality;
        this.labelFinality = labelFinality;
        this.predictedAt = predictedAt;
        this.labeledAt = labeledAt;
        this.createdAt = createdAt;
        this.observationPayloadJson = observationPayloadJson;
    }

    public UUID getId() { return id; }
    public UUID getObservationId() { return observationId; }
    public String getEngineType() { return engineType; }
    public UUID getSourceEvaluationId() { return sourceEvaluationId; }
    public UUID getLabelSourceId() { return labelSourceId; }
    public String getPolicyVersion() { return policyVersion; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getMappingVersion() { return mappingVersion; }
    public String getAggregationVersion() { return aggregationVersion; }
    public String getCalibrationMappingVersion() { return calibrationMappingVersion; }
    public String getCalibrationPolicyVersion() { return calibrationPolicyVersion; }
    public String getObservationHorizon() { return observationHorizon; }
    public String getCohortKey() { return cohortKey; }
    public String getAttributionQuality() { return attributionQuality; }
    public String getLabelQuality() { return labelQuality; }
    public String getLabelFinality() { return labelFinality; }
    public Instant getPredictedAt() { return predictedAt; }
    public Instant getLabeledAt() { return labeledAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getObservationPayloadJson() { return observationPayloadJson; }
}