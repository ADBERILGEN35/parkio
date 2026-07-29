package com.parkio.parking.infrastructure.persistence.outcome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;
import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.outcome.OutcomeSnapshot;
import com.parkio.parking.outcome.confidence.OutcomeConfidence;
import com.parkio.parking.outcome.evaluation.OutcomeEvaluationContext;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;
import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import com.parkio.parking.outcome.history.OutcomeSnapshotSchemaVersion;
import com.parkio.parking.outcome.policy.OutcomePolicyVersion;
import com.parkio.parking.outcome.signal.OutcomeSignal;
import com.parkio.parking.outcome.signal.OutcomeSignalSource;
import com.parkio.parking.outcome.signal.OutcomeSignalType;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import com.parkio.parking.infrastructure.persistence.entity.OutcomeHistoryEntity;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class OutcomeHistorySnapshotMapper {

    private final ObjectMapper objectMapper;

    public OutcomeHistorySnapshotMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OutcomeHistoryEntity toEntity(OutcomeHistoryRecord record) {
        try {
            return new OutcomeHistoryEntity(
                    record.recordId(),
                    record.evaluationId(),
                    record.parkingSpotId(),
                    record.policyVersion().value(),
                    record.snapshotSchemaVersion(),
                    record.triggerType().name(),
                    record.triggerReference(),
                    record.evaluatedAt(),
                    record.evidenceCutoffAt(),
                    record.classification().name(),
                    record.confidence().value(),
                    record.primaryReason().name(),
                    record.validationWindowOpen(),
                    objectMapper.writeValueAsString(toNode(record)),
                    record.createdAt());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize outcome history snapshot", ex);
        }
    }

    public OutcomeHistoryRecord toDomain(OutcomeHistoryEntity entity) {
        try {
            JsonNode root = objectMapper.readTree(entity.getSnapshotJson());
            requireSchema(root);
            OutcomeSnapshot snapshot = readSnapshot(root.get("snapshot"));
            return new OutcomeHistoryRecord(
                    entity.getId(),
                    entity.getEvaluationId(),
                    entity.getParkingSpotId(),
                    OutcomePolicyVersion.of(entity.getPolicyVersion()),
                    entity.getSnapshotSchemaVersion(),
                    OutcomeEvaluationTrigger.valueOf(entity.getTriggerType()),
                    entity.getTriggerReference(),
                    entity.getEvaluatedAt(),
                    entity.getEvidenceCutoffAt(),
                    snapshot,
                    OutcomeClassification.valueOf(entity.getClassification()),
                    OutcomeConfidence.of(entity.getConfidence()),
                    OutcomeReason.valueOf(entity.getPrimaryReason()),
                    entity.isValidationWindowOpen(),
                    entity.getCreatedAt());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize outcome history snapshot", ex);
        }
    }

    private ObjectNode toNode(OutcomeHistoryRecord record) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", OutcomeSnapshotSchemaVersion.V1);
        root.put("recordId", record.recordId().toString());
        root.put("evaluationId", record.evaluationId().toString());
        root.put("parkingSpotId", record.parkingSpotId().toString());
        root.put("policyVersion", record.policyVersion().value());
        root.put("snapshotSchemaVersion", record.snapshotSchemaVersion());
        root.put("triggerType", record.triggerType().name());
        root.put("triggerReference", record.triggerReference().toString());
        root.put("evaluatedAt", record.evaluatedAt().toString());
        root.put("evidenceCutoffAt", record.evidenceCutoffAt().toString());
        root.put("classification", record.classification().name());
        root.put("confidence", record.confidence().value());
        root.put("primaryReason", record.primaryReason().name());
        root.put("validationWindowOpen", record.validationWindowOpen());
        root.put("createdAt", record.createdAt().toString());
        root.set("snapshot", writeSnapshot(record.snapshot()));
        return root;
    }

    private ObjectNode writeSnapshot(OutcomeSnapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("evidence", writeEvidence(snapshot.evidence()));
        node.set("context", writeContext(snapshot.context()));
        node.set("evaluation", writeEvaluation(snapshot.evaluation()));
        return node;
    }

    private OutcomeSnapshot readSnapshot(JsonNode node) {
        return new OutcomeSnapshot(
                readEvidence(node.get("evidence")),
                readContext(node.get("context")),
                readEvaluation(node.get("evaluation")));
    }

    private ObjectNode writeEvidence(OutcomeEvidence evidence) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("parkingSpotId", evidence.parkingSpotId().toString());
        node.put("status", evidence.status().name());
        node.put("createdAt", evidence.createdAt().toString());
        if (evidence.activatedAt() != null) {
            node.put("activatedAt", evidence.activatedAt().toString());
        }
        if (evidence.expiresAt() != null) {
            node.put("expiresAt", evidence.expiresAt().toString());
        }
        if (evidence.updatedAt() != null) {
            node.put("updatedAt", evidence.updatedAt().toString());
        }
        node.put("verificationCount", evidence.verificationCount());
        node.put("filledReportCount", evidence.filledReportCount());
        node.put("confidenceScore", evidence.confidenceScore());
        node.set("timeline", writeTimeline(evidence.timeline()));
        return node;
    }

    private OutcomeEvidence readEvidence(JsonNode node) {
        return new OutcomeEvidence(
                UUID.fromString(text(node, "parkingSpotId")),
                ParkingSpotStatus.valueOf(text(node, "status")),
                Instant.parse(text(node, "createdAt")),
                instant(node, "activatedAt"),
                instant(node, "expiresAt"),
                instant(node, "updatedAt"),
                node.get("verificationCount").asInt(),
                node.get("filledReportCount").asInt(),
                node.get("confidenceScore").asDouble(),
                readTimeline(node.get("timeline")));
    }

    private ObjectNode writeContext(OutcomeEvaluationContext context) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("evaluatedAt", context.evaluatedAt().toString());
        node.put("policyVersion", context.policyVersion().value());
        node.put("validationWindowSeconds", context.validationWindow().toSeconds());
        return node;
    }

    private OutcomeEvaluationContext readContext(JsonNode node) {
        return new OutcomeEvaluationContext(
                Instant.parse(text(node, "evaluatedAt")),
                OutcomePolicyVersion.of(text(node, "policyVersion")),
                Duration.ofSeconds(node.get("validationWindowSeconds").asLong()));
    }

    private ObjectNode writeEvaluation(OutcomeEvaluation evaluation) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("parkingSpotId", evaluation.parkingSpotId().toString());
        node.put("classification", evaluation.classification().name());
        node.put("confidence", evaluation.confidence().value());
        node.put("primaryReason", evaluation.primaryReason().name());
        ArrayNode reasons = objectMapper.createArrayNode();
        for (OutcomeReason reason : evaluation.reasons()) {
            reasons.add(reason.name());
        }
        node.set("reasons", reasons);
        node.set("timeline", writeTimeline(evaluation.timeline()));
        node.put("validationAgeSeconds", evaluation.validationAge().toSeconds());
        node.put("validationWindowOpen", evaluation.validationWindowOpen());
        node.put("policyVersion", evaluation.policyVersion().value());
        node.put("evaluatedAt", evaluation.evaluatedAt().toString());
        return node;
    }

    private OutcomeEvaluation readEvaluation(JsonNode node) {
        List<OutcomeReason> reasons = new ArrayList<>();
        for (JsonNode reasonNode : node.get("reasons")) {
            reasons.add(OutcomeReason.valueOf(reasonNode.asText()));
        }
        return new OutcomeEvaluation(
                UUID.fromString(text(node, "parkingSpotId")),
                OutcomeClassification.valueOf(text(node, "classification")),
                OutcomeConfidence.of(node.get("confidence").asInt()),
                OutcomeReason.valueOf(text(node, "primaryReason")),
                Set.copyOf(reasons),
                readTimeline(node.get("timeline")),
                Duration.ofSeconds(node.get("validationAgeSeconds").asLong()),
                node.get("validationWindowOpen").asBoolean(),
                OutcomePolicyVersion.of(text(node, "policyVersion")),
                Instant.parse(text(node, "evaluatedAt")));
    }

    private ObjectNode writeTimeline(OutcomeTimeline timeline) {
        ObjectNode node = objectMapper.createObjectNode();
        if (timeline.publishedAt() != null) {
            node.put("publishedAt", timeline.publishedAt().toString());
        }
        if (timeline.firstSignalAt() != null) {
            node.put("firstSignalAt", timeline.firstSignalAt().toString());
        }
        if (timeline.latestSignalAt() != null) {
            node.put("latestSignalAt", timeline.latestSignalAt().toString());
        }
        if (timeline.validationWindowEnd() != null) {
            node.put("validationWindowEnd", timeline.validationWindowEnd().toString());
        }
        ArrayNode signals = objectMapper.createArrayNode();
        for (OutcomeSignal signal : timeline.signals()) {
            ObjectNode signalNode = objectMapper.createObjectNode();
            signalNode.put("type", signal.type().name());
            signalNode.put("source", signal.source().name());
            signalNode.put("occurredAt", signal.occurredAt().toString());
            signals.add(signalNode);
        }
        node.set("signals", signals);
        return node;
    }

    private OutcomeTimeline readTimeline(JsonNode node) {
        List<OutcomeSignal> signals = new ArrayList<>();
        for (JsonNode signalNode : node.get("signals")) {
            signals.add(new OutcomeSignal(
                    OutcomeSignalType.valueOf(text(signalNode, "type")),
                    OutcomeSignalSource.valueOf(text(signalNode, "source")),
                    Instant.parse(text(signalNode, "occurredAt"))));
        }
        return new OutcomeTimeline(
                instant(node, "publishedAt"),
                instant(node, "firstSignalAt"),
                instant(node, "latestSignalAt"),
                instant(node, "validationWindowEnd"),
                signals);
    }

    private static void requireSchema(JsonNode root) {
        if (!OutcomeSnapshotSchemaVersion.V1.equals(text(root, "schemaVersion"))) {
            throw new IllegalStateException("Unsupported outcome snapshot schema: " + text(root, "schemaVersion"));
        }
    }

    private static Instant instant(JsonNode node, String field) {
        return node.hasNonNull(field) ? Instant.parse(text(node, field)) : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("Missing snapshot field: " + field);
        }
        return value.asText();
    }
}