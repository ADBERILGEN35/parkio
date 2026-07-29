package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_evaluation_ledger")
public class FraudLedgerEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "evaluation_id", nullable = false, updatable = false)
    private UUID evaluationId;

    @Column(name = "subject_type", nullable = false, updatable = false, length = 64)
    private String subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "fraud_domain", nullable = false, updatable = false, length = 64)
    private String fraudDomain;

    @Column(name = "policy_version", nullable = false, updatable = false, length = 64)
    private String policyVersion;

    @Column(name = "schema_version", nullable = false, updatable = false, length = 64)
    private String schemaVersion;

    @Column(name = "mapping_version", nullable = false, updatable = false, length = 64)
    private String mappingVersion;

    @Column(name = "aggregation_version", nullable = false, updatable = false, length = 64)
    private String aggregationVersion;

    @Column(name = "source_outcome_record_id", nullable = false, updatable = false)
    private UUID sourceOutcomeRecordId;

    @Column(name = "evidence_window_start", nullable = false, updatable = false)
    private Instant evidenceWindowStart;

    @Column(name = "evidence_window_end", nullable = false, updatable = false)
    private Instant evidenceWindowEnd;

    @Column(name = "risk_score", nullable = false, updatable = false)
    private int riskScore;

    @Column(name = "risk_band", nullable = false, updatable = false, length = 64)
    private String riskBand;

    @Column(name = "confidence_band", nullable = false, updatable = false, length = 64)
    private String confidenceBand;

    @Column(name = "effective_evidence_count", nullable = false, updatable = false)
    private int effectiveEvidenceCount;

    @Column(name = "disposition", nullable = false, updatable = false, length = 64)
    private String disposition;

    @Column(name = "decisive_rule", nullable = false, updatable = false, length = 128)
    private String decisiveRule;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "evaluation_snapshot_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String evaluationSnapshotJson;

    protected FraudLedgerEntity() {}

    public FraudLedgerEntity(
            UUID id,
            UUID evaluationId,
            String subjectType,
            UUID subjectId,
            String fraudDomain,
            String policyVersion,
            String schemaVersion,
            String mappingVersion,
            String aggregationVersion,
            UUID sourceOutcomeRecordId,
            Instant evidenceWindowStart,
            Instant evidenceWindowEnd,
            int riskScore,
            String riskBand,
            String confidenceBand,
            int effectiveEvidenceCount,
            String disposition,
            String decisiveRule,
            Instant evaluatedAt,
            Instant createdAt,
            String evaluationSnapshotJson) {
        this.id = id;
        this.evaluationId = evaluationId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.fraudDomain = fraudDomain;
        this.policyVersion = policyVersion;
        this.schemaVersion = schemaVersion;
        this.mappingVersion = mappingVersion;
        this.aggregationVersion = aggregationVersion;
        this.sourceOutcomeRecordId = sourceOutcomeRecordId;
        this.evidenceWindowStart = evidenceWindowStart;
        this.evidenceWindowEnd = evidenceWindowEnd;
        this.riskScore = riskScore;
        this.riskBand = riskBand;
        this.confidenceBand = confidenceBand;
        this.effectiveEvidenceCount = effectiveEvidenceCount;
        this.disposition = disposition;
        this.decisiveRule = decisiveRule;
        this.evaluatedAt = evaluatedAt;
        this.createdAt = createdAt;
        this.evaluationSnapshotJson = evaluationSnapshotJson;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEvaluationId() {
        return evaluationId;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getFraudDomain() {
        return fraudDomain;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getMappingVersion() {
        return mappingVersion;
    }

    public String getAggregationVersion() {
        return aggregationVersion;
    }

    public UUID getSourceOutcomeRecordId() {
        return sourceOutcomeRecordId;
    }

    public Instant getEvidenceWindowStart() {
        return evidenceWindowStart;
    }

    public Instant getEvidenceWindowEnd() {
        return evidenceWindowEnd;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public String getRiskBand() {
        return riskBand;
    }

    public String getConfidenceBand() {
        return confidenceBand;
    }

    public int getEffectiveEvidenceCount() {
        return effectiveEvidenceCount;
    }

    public String getDisposition() {
        return disposition;
    }

    public String getDecisiveRule() {
        return decisiveRule;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getEvaluationSnapshotJson() {
        return evaluationSnapshotJson;
    }
}
