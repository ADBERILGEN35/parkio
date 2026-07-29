package com.parkio.parking.infrastructure.persistence.fraud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.fraud.FraudAssessment;
import com.parkio.parking.fraud.FraudAssessmentCategory;
import com.parkio.parking.fraud.FraudAssessmentLevel;
import com.parkio.parking.fraud.FraudAttributionQuality;
import com.parkio.parking.fraud.FraudConfidenceBand;
import com.parkio.parking.fraud.FraudDisposition;
import com.parkio.parking.fraud.FraudDomain;
import com.parkio.parking.fraud.FraudEvaluation;
import com.parkio.parking.fraud.FraudEvaluationContext;
import com.parkio.parking.fraud.FraudEvidenceVolume;
import com.parkio.parking.fraud.FraudFeatureVector;
import com.parkio.parking.fraud.FraudHardAnomalyType;
import com.parkio.parking.fraud.FraudLedgerEntry;
import com.parkio.parking.fraud.FraudRiskBand;
import com.parkio.parking.fraud.FraudRiskScore;
import com.parkio.parking.fraud.FraudSnapshot;
import com.parkio.parking.fraud.FraudSnapshotSchemaVersion;
import com.parkio.parking.fraud.FraudSubject;
import com.parkio.parking.fraud.FraudSubjectType;
import com.parkio.parking.infrastructure.persistence.entity.FraudLedgerEntity;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Serializes canonical fraud-domain payloads for append-only persistence. */
public final class FraudPersistenceMapper {

    private final ObjectMapper objectMapper;

    public FraudPersistenceMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public FraudLedgerEntity toEntity(FraudLedgerEntry entry) {
        return new FraudLedgerEntity(
                entry.ledgerEntryId(),
                entry.evaluationId(),
                entry.subject().type().name(),
                entry.subject().subjectId(),
                entry.domain().name(),
                entry.policyVersion(),
                entry.snapshotSchemaVersion().value(),
                entry.mappingVersion(),
                entry.aggregationVersion(),
                entry.sourceOutcomeRecordId(),
                entry.evidenceWindowStart(),
                entry.evidenceWindowEnd(),
                entry.riskScoreBasisPoints(),
                entry.riskBand().name(),
                entry.confidenceBand().name(),
                entry.effectiveEvidenceCount(),
                entry.disposition().name(),
                entry.decisiveRule(),
                entry.evaluatedAt(),
                entry.createdAt(),
                writeJson(writeSnapshot(entry.snapshot())));
    }

    public FraudLedgerEntry toDomain(FraudLedgerEntity entity) {
        FraudSnapshot snapshot = readSnapshot(readTree(entity.getEvaluationSnapshotJson()));
        return new FraudLedgerEntry(
                entity.getId(),
                entity.getEvaluationId(),
                snapshot.subject(),
                snapshot.domain(),
                entity.getPolicyVersion(),
                snapshot.snapshotSchemaVersion(),
                entity.getMappingVersion(),
                entity.getAggregationVersion(),
                entity.getSourceOutcomeRecordId(),
                entity.getEvidenceWindowStart(),
                entity.getEvidenceWindowEnd(),
                entity.getRiskScore(),
                FraudRiskBand.valueOf(entity.getRiskBand()),
                FraudConfidenceBand.valueOf(entity.getConfidenceBand()),
                entity.getEffectiveEvidenceCount(),
                FraudDisposition.valueOf(entity.getDisposition()),
                entity.getDecisiveRule(),
                entity.getEvaluatedAt(),
                entity.getCreatedAt(),
                snapshot);
    }

    private JsonNode writeSnapshot(FraudSnapshot snapshot) {
        var root = objectMapper.createObjectNode();
        root.put("subjectType", snapshot.subject().type().name());
        root.put("subjectId", snapshot.subject().subjectId().toString());
        root.put("domain", snapshot.domain().name());
        root.put("policyVersion", snapshot.policyVersion());
        root.put("snapshotSchemaVersion", snapshot.snapshotSchemaVersion().value());
        root.put("mappingVersion", snapshot.mappingVersion());
        root.put("aggregationVersion", snapshot.aggregationVersion());
        root.put("evaluatedAt", snapshot.evaluatedAt().toString());
        root.set("context", writeContext(snapshot.context()));
        root.set("featureVector", writeFeatureVector(snapshot.featureVector()));
        root.set("evaluation", writeEvaluation(snapshot.evaluation()));
        return root;
    }

    private JsonNode writeContext(FraudEvaluationContext context) {
        var node = objectMapper.createObjectNode();
        node.put("evaluatedAt", context.evaluatedAt().toString());
        node.put("policyVersion", context.policyVersion());
        node.put("snapshotSchemaVersion", context.snapshotSchemaVersion().value());
        node.put("mappingVersion", context.mappingVersion());
        return node;
    }

    private JsonNode writeFeatureVector(FraudFeatureVector features) {
        var node = objectMapper.createObjectNode();
        node.put("windowStart", features.windowStart().toString());
        node.put("windowEnd", features.windowEnd().toString());
        node.put("sourceWatermarkOutcomeRecordId", features.sourceWatermarkOutcomeRecordId().toString());
        node.put("sourceWatermarkEvaluatedAt", features.sourceWatermarkEvaluatedAt().toString());
        node.put("eligibleContributionCount", features.eligibleContributionCount());
        node.put("directConfirmedIncorrectCount", features.directConfirmedIncorrectCount());
        node.put("likelyIncorrectCount", features.likelyIncorrectCount());
        node.put("confirmedCorrectCount", features.confirmedCorrectCount());
        node.put("unknownCount", features.unknownCount());
        node.put("expiredWithoutEvidenceCount", features.expiredWithoutEvidenceCount());
        node.put("aggregationVersion", features.aggregationVersion());
        return node;
    }

    private JsonNode writeEvaluation(FraudEvaluation evaluation) {
        var node = objectMapper.createObjectNode();
        node.put("riskScore", evaluation.riskScore().basisPoints());
        node.put("riskBand", evaluation.riskBand().name());
        node.put("confidenceBand", evaluation.confidenceBand().name());
        node.put("evidenceVolume", evaluation.evidenceVolume().count());
        node.put("disposition", evaluation.disposition().name());
        node.put("decisiveRule", evaluation.decisiveRule());
        node.put("policyVersion", evaluation.policyVersion());
        node.put("evaluatedAt", evaluation.evaluatedAt().toString());
        node.put("windowStart", evaluation.windowStart().toString());
        node.put("windowEnd", evaluation.windowEnd().toString());
        evaluation.hardAnomaly().ifPresentOrElse(
                type -> node.put("hardAnomaly", type.name()),
                () -> node.putNull("hardAnomaly"));
        var assessments = objectMapper.createArrayNode();
        for (FraudAssessment assessment : evaluation.assessments()) {
            var item = objectMapper.createObjectNode();
            item.put("category", assessment.category().name());
            item.put("level", assessment.level().name());
            item.put("contributionBasisPoints", assessment.contributionBasisPoints());
            item.put("attributionQuality", assessment.attributionQuality().name());
            item.put("evidenceCount", assessment.evidenceCount());
            item.put("decisiveReason", assessment.decisiveReason());
            assessments.add(item);
        }
        node.set("assessments", assessments);
        return node;
    }

    private FraudSnapshot readSnapshot(JsonNode root) {
        FraudSubject subject = new FraudSubject(
                FraudSubjectType.valueOf(root.get("subjectType").asText()),
                UUID.fromString(root.get("subjectId").asText()));
        FraudDomain domain = FraudDomain.valueOf(root.get("domain").asText());
        FraudEvaluationContext context = readContext(root.get("context"));
        FraudFeatureVector features = readFeatureVector(root.get("featureVector"), subject, domain);
        FraudEvaluation evaluation = readEvaluation(root.get("evaluation"), subject, domain);
        return new FraudSnapshot(
                subject,
                domain,
                root.get("policyVersion").asText(),
                FraudSnapshotSchemaVersion.fromValue(root.get("snapshotSchemaVersion").asText()),
                root.get("mappingVersion").asText(),
                root.get("aggregationVersion").asText(),
                context,
                features,
                evaluation,
                Instant.parse(root.get("evaluatedAt").asText()));
    }

    private FraudEvaluationContext readContext(JsonNode node) {
        return new FraudEvaluationContext(
                Instant.parse(node.get("evaluatedAt").asText()),
                node.get("policyVersion").asText(),
                FraudSnapshotSchemaVersion.fromValue(node.get("snapshotSchemaVersion").asText()),
                node.get("mappingVersion").asText());
    }

    private FraudFeatureVector readFeatureVector(JsonNode node, FraudSubject subject, FraudDomain domain) {
        return new FraudFeatureVector(
                subject,
                domain,
                Instant.parse(node.get("windowStart").asText()),
                Instant.parse(node.get("windowEnd").asText()),
                UUID.fromString(node.get("sourceWatermarkOutcomeRecordId").asText()),
                Instant.parse(node.get("sourceWatermarkEvaluatedAt").asText()),
                node.get("eligibleContributionCount").asInt(),
                node.get("directConfirmedIncorrectCount").asInt(),
                node.get("likelyIncorrectCount").asInt(),
                node.get("confirmedCorrectCount").asInt(),
                node.get("unknownCount").asInt(),
                node.get("expiredWithoutEvidenceCount").asInt(),
                node.get("aggregationVersion").asText());
    }

    private FraudEvaluation readEvaluation(JsonNode node, FraudSubject subject, FraudDomain domain) {
        List<FraudAssessment> assessments = new ArrayList<>();
        for (JsonNode item : node.withArray("assessments")) {
            assessments.add(new FraudAssessment(
                    FraudAssessmentCategory.valueOf(item.get("category").asText()),
                    FraudAssessmentLevel.valueOf(item.get("level").asText()),
                    item.get("contributionBasisPoints").asInt(),
                    FraudAttributionQuality.valueOf(item.get("attributionQuality").asText()),
                    item.get("evidenceCount").asInt(),
                    item.get("decisiveReason").asText()));
        }
        Optional<FraudHardAnomalyType> hardAnomaly = node.get("hardAnomaly").isNull()
                ? Optional.empty()
                : Optional.of(FraudHardAnomalyType.valueOf(node.get("hardAnomaly").asText()));
        return new FraudEvaluation(
                subject,
                domain,
                assessments,
                hardAnomaly,
                FraudRiskScore.of(node.get("riskScore").asInt()),
                FraudRiskBand.valueOf(node.get("riskBand").asText()),
                FraudConfidenceBand.valueOf(node.get("confidenceBand").asText()),
                FraudEvidenceVolume.of(node.get("evidenceVolume").asInt()),
                FraudDisposition.valueOf(node.get("disposition").asText()),
                node.get("decisiveRule").asText(),
                node.get("policyVersion").asText(),
                Instant.parse(node.get("evaluatedAt").asText()),
                Instant.parse(node.get("windowStart").asText()),
                Instant.parse(node.get("windowEnd").asText()));
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
