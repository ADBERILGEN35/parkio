package com.parkio.parking.infrastructure.persistence.trust;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.parking.infrastructure.persistence.entity.TrustLedgerEntity;
import com.parkio.parking.infrastructure.persistence.entity.TrustSnapshotEntity;
import com.parkio.parking.trust.TrustConfidence;
import com.parkio.parking.trust.TrustDomain;
import com.parkio.parking.trust.TrustEvaluation;
import com.parkio.parking.trust.TrustEvidence;
import com.parkio.parking.trust.TrustLedgerEntry;
import com.parkio.parking.trust.TrustScore;
import com.parkio.parking.trust.TrustSnapshot;
import com.parkio.parking.trust.TrustSnapshotSchemaVersion;
import com.parkio.parking.trust.TrustSubject;
import com.parkio.parking.trust.TrustSubjectType;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Serializes canonical trust-domain payloads for append-only persistence. */
public final class TrustPersistenceMapper {

    private final ObjectMapper objectMapper;

    public TrustPersistenceMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TrustLedgerEntity toEntity(TrustLedgerEntry entry) {
        return new TrustLedgerEntity(
                entry.ledgerEntryId(),
                entry.evaluationId(),
                entry.subject().type().name(),
                entry.subject().subjectId(),
                entry.domain().name(),
                entry.trustPolicyVersion(),
                entry.snapshotSchemaVersion().value(),
                entry.attributionMappingVersion(),
                entry.sourceOutcomeRecordId(),
                entry.sourceEvidenceId(),
                entry.sourceEvidenceGroupId(),
                entry.evidenceType().name(),
                entry.contributionRole().name(),
                entry.attributionQuality().name(),
                entry.eligibility().name(),
                entry.direction().name(),
                entry.trustLevel().name(),
                entry.evaluatedAt(),
                entry.createdAt(),
                writeJson(writeEvidence(entry.evidence())),
                writeJson(writeSnapshot(entry.previousSnapshot())),
                writeJson(writeEvaluation(entry.evaluation())));
    }

    public TrustLedgerEntry toDomain(TrustLedgerEntity entity) {
        TrustEvidence evidence = readEvidence(readTree(entity.getEvidenceJson()));
        TrustSnapshot previousSnapshot = readSnapshot(readTree(entity.getPreviousSnapshotJson()));
        TrustEvaluation evaluation = readEvaluation(readTree(entity.getEvaluationJson()));
        return new TrustLedgerEntry(
                entity.getId(),
                entity.getEvaluationId(),
                new TrustSubject(TrustSubjectType.valueOf(entity.getSubjectType()), entity.getSubjectId()),
                TrustDomain.valueOf(entity.getTrustDomain()),
                entity.getTrustPolicyVersion(),
                readSchemaVersion(entity.getSnapshotSchemaVersion()),
                entity.getAttributionMappingVersion(),
                entity.getSourceOutcomeRecordId(),
                entity.getSourceEvidenceId(),
                entity.getSourceEvidenceGroupId(),
                TrustEvidence.Type.valueOf(entity.getEvidenceType()),
                TrustEvidence.ContributionRole.valueOf(entity.getContributionRole()),
                TrustEvidence.AttributionQuality.valueOf(entity.getAttributionQuality()),
                TrustEvidence.Eligibility.valueOf(entity.getEligibility()),
                TrustEvaluation.Direction.valueOf(entity.getUpdateDirection()),
                TrustSnapshot.Level.valueOf(entity.getTrustLevel()),
                entity.getEvaluatedAt(),
                entity.getCreatedAt(),
                evidence,
                previousSnapshot,
                evaluation);
    }

    public TrustSnapshotEntity toEntity(TrustSnapshot snapshot, Instant createdAt, Instant updatedAt, Long version) {
        UUID id = UUID.nameUUIDFromBytes(
                ("trust-snapshot|" + snapshot.subject().type().name() + "|" + snapshot.subject().subjectId() + "|"
                        + snapshot.domain().name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new TrustSnapshotEntity(
                id,
                snapshot.subject().type().name(),
                snapshot.subject().subjectId(),
                snapshot.domain().name(),
                snapshot.trustPolicyVersion(),
                snapshot.snapshotSchemaVersion().value(),
                snapshot.lastEvaluatedAt(),
                writeJson(writeSnapshot(snapshot)),
                createdAt,
                updatedAt,
                version);
    }

    public TrustSnapshot toDomain(TrustSnapshotEntity entity) {
        return readSnapshot(readTree(entity.getSnapshotJson()));
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

    private ObjectNode writeEvidence(TrustEvidence evidence) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("evidenceId", evidence.evidenceId().toString());
        node.put("evidenceGroupId", evidence.evidenceGroupId().toString());
        node.put("subjectType", evidence.subject().type().name());
        node.put("subjectId", evidence.subject().subjectId().toString());
        node.put("domain", evidence.domain().name());
        node.put("evidenceType", evidence.evidenceType().name());
        node.put("contributionRole", evidence.contributionRole().name());
        node.put("attributionQuality", evidence.attributionQuality().name());
        node.put("eligibility", evidence.eligibility().name());
        node.put("outcomeClassification", evidence.outcomeClassification().name());
        node.put("outcomeConfidence", evidence.outcomeConfidence());
        node.put("primaryOutcomeReason", evidence.primaryOutcomeReason().name());
        ArrayNode reasons = node.putArray("outcomeReasons");
        evidence.outcomeReasons().stream().map(Enum::name).sorted().forEach(reasons::add);
        node.put("sourceOutcomeRecordId", evidence.sourceOutcomeRecordId().toString());
        node.put("sourceOutcomeEvaluationId", evidence.sourceOutcomeEvaluationId().toString());
        node.put("parkingSpotId", evidence.parkingSpotId().toString());
        node.put("occurredAt", evidence.occurredAt().toString());
        node.put("validatedAt", evidence.validatedAt().toString());
        node.put("outcomePolicyVersion", evidence.outcomePolicyVersion());
        node.put("attributionMappingVersion", evidence.attributionMappingVersion());
        return node;
    }

    private TrustEvidence readEvidence(JsonNode node) {
        Set<com.parkio.parking.outcome.OutcomeReason> reasons = new LinkedHashSet<>();
        node.withArray("outcomeReasons").forEach(item ->
                reasons.add(com.parkio.parking.outcome.OutcomeReason.valueOf(item.asText())));
        return new TrustEvidence(
                UUID.fromString(text(node, "evidenceId")),
                UUID.fromString(text(node, "evidenceGroupId")),
                new TrustSubject(
                        TrustSubjectType.valueOf(text(node, "subjectType")),
                        UUID.fromString(text(node, "subjectId"))),
                TrustDomain.valueOf(text(node, "domain")),
                TrustEvidence.Type.valueOf(text(node, "evidenceType")),
                TrustEvidence.ContributionRole.valueOf(text(node, "contributionRole")),
                TrustEvidence.AttributionQuality.valueOf(text(node, "attributionQuality")),
                TrustEvidence.Eligibility.valueOf(text(node, "eligibility")),
                com.parkio.parking.outcome.OutcomeClassification.valueOf(text(node, "outcomeClassification")),
                node.path("outcomeConfidence").asInt(),
                com.parkio.parking.outcome.OutcomeReason.valueOf(text(node, "primaryOutcomeReason")),
                reasons,
                UUID.fromString(text(node, "sourceOutcomeRecordId")),
                UUID.fromString(text(node, "sourceOutcomeEvaluationId")),
                UUID.fromString(text(node, "parkingSpotId")),
                Instant.parse(text(node, "occurredAt")),
                Instant.parse(text(node, "validatedAt")),
                text(node, "outcomePolicyVersion"),
                text(node, "attributionMappingVersion"));
    }

    private ObjectNode writeSnapshot(TrustSnapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("subjectType", snapshot.subject().type().name());
        node.put("subjectId", snapshot.subject().subjectId().toString());
        node.put("domain", snapshot.domain().name());
        node.put("trustPolicyVersion", snapshot.trustPolicyVersion());
        node.put("snapshotSchemaVersion", snapshot.snapshotSchemaVersion().value());
        node.put("scoreBasisPoints", snapshot.score().basisPoints());
        node.put("confidenceBasisPoints", snapshot.confidence().basisPoints());
        node.put("positiveEvidenceMass", snapshot.positiveEvidenceMass());
        node.put("negativeEvidenceMass", snapshot.negativeEvidenceMass());
        node.put("effectiveEvidenceCount", snapshot.effectiveEvidenceCount());
        node.put("level", snapshot.level().name());
        if (snapshot.lastEvaluatedAt() != null) {
            node.put("lastEvaluatedAt", snapshot.lastEvaluatedAt().toString());
        }
        return node;
    }

    private TrustSnapshot readSnapshot(JsonNode node) {
        return new TrustSnapshot(
                new TrustSubject(
                        TrustSubjectType.valueOf(text(node, "subjectType")),
                        UUID.fromString(text(node, "subjectId"))),
                TrustDomain.valueOf(text(node, "domain")),
                text(node, "trustPolicyVersion"),
                readSchemaVersion(text(node, "snapshotSchemaVersion")),
                TrustScore.of(node.path("scoreBasisPoints").asInt()),
                TrustConfidence.of(node.path("confidenceBasisPoints").asInt()),
                node.path("positiveEvidenceMass").asInt(),
                node.path("negativeEvidenceMass").asInt(),
                node.path("effectiveEvidenceCount").asInt(),
                TrustSnapshot.Level.valueOf(text(node, "level")),
                node.hasNonNull("lastEvaluatedAt") ? Instant.parse(text(node, "lastEvaluatedAt")) : null);
    }

    private ObjectNode writeEvaluation(TrustEvaluation evaluation) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("evidence", writeEvidence(evaluation.evidence()));
        node.set("previousSnapshot", writeSnapshot(evaluation.previousSnapshot()));
        node.set("resultingSnapshot", writeSnapshot(evaluation.resultingSnapshot()));
        node.put("positiveEvidenceDelta", evaluation.positiveEvidenceDelta());
        node.put("negativeEvidenceDelta", evaluation.negativeEvidenceDelta());
        node.put("direction", evaluation.direction().name());
        node.put("decisiveReason", evaluation.decisiveReason());
        node.put("trustPolicyVersion", evaluation.trustPolicyVersion());
        node.put("evaluatedAt", evaluation.evaluatedAt().toString());
        return node;
    }

    private TrustEvaluation readEvaluation(JsonNode node) {
        return new TrustEvaluation(
                readEvidence(node.path("evidence")),
                readSnapshot(node.path("previousSnapshot")),
                readSnapshot(node.path("resultingSnapshot")),
                node.path("positiveEvidenceDelta").asInt(),
                node.path("negativeEvidenceDelta").asInt(),
                TrustEvaluation.Direction.valueOf(text(node, "direction")),
                text(node, "decisiveReason"),
                text(node, "trustPolicyVersion"),
                Instant.parse(text(node, "evaluatedAt")));
    }

    private static TrustSnapshotSchemaVersion readSchemaVersion(String value) {
        for (TrustSnapshotSchemaVersion version : TrustSnapshotSchemaVersion.values()) {
            if (version.value().equals(value)) {
                return version;
            }
        }
        throw new IllegalArgumentException("Unknown trust snapshot schema version: " + value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing field " + field);
        }
        return value.asText();
    }
}

