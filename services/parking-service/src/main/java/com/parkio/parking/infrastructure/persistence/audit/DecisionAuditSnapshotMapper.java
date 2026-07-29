package com.parkio.parking.infrastructure.persistence.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.parking.decision.DecisionResult;
import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.assessment.AssessmentBundle;
import com.parkio.parking.decision.assessment.AssessmentCategory;
import com.parkio.parking.decision.assessment.AssessmentCompleteness;
import com.parkio.parking.decision.assessment.AssessmentLevel;
import com.parkio.parking.decision.assessment.AssessmentVersion;
import com.parkio.parking.decision.assessment.DerivedAssessment;
import com.parkio.parking.decision.assessment.DomainAssessment;
import com.parkio.parking.decision.assessment.EvidenceReference;
import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.assessment.RiskAssessment;
import com.parkio.parking.decision.audit.DecisionAuditRecord;
import com.parkio.parking.decision.authority.DecisionExecutionMode;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.decision.audit.DecisionAuditSnapshotSchema;
import com.parkio.parking.decision.calibration.DecisivePolicyRule;
import com.parkio.parking.decision.calibration.EvidenceAvailabilityProfile;
import com.parkio.parking.decision.calibration.HardConstraintFamily;
import com.parkio.parking.decision.calibration.RiskBand;
import com.parkio.parking.decision.evaluation.EvaluationContext;
import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidencePolarity;
import com.parkio.parking.decision.evidence.EvidenceSource;
import com.parkio.parking.decision.evidence.EvidenceType;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.score.EvidenceScore;
import com.parkio.parking.decision.score.RiskScore;
import com.parkio.parking.decision.shadow.LegacyPublicationOutcome;
import com.parkio.parking.decision.shadow.ShadowComparisonCategory;
import com.parkio.parking.infrastructure.persistence.entity.DecisionAuditEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Maps {@link DecisionAuditRecord} to/from append-only entity + TEXT JSON snapshot.
 * Jackson lives only in infrastructure.
 */
public final class DecisionAuditSnapshotMapper {

    private final ObjectMapper objectMapper;

    public DecisionAuditSnapshotMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public DecisionAuditEntity toEntity(DecisionAuditRecord record) {
        Objects.requireNonNull(record, "record");
        try {
            String json = objectMapper.writeValueAsString(toSnapshotNode(record));
            return new DecisionAuditEntity(
                    record.auditId(),
                    record.parkingSpotId(),
                    record.evaluationId(),
                    record.policyVersion(),
                    record.decisionEngineVersion(),
                    record.shadowModeVersion(),
                    record.evaluatedAt(),
                    record.disposition().name(),
                    record.comparisonCategory().name(),
                    record.decisiveRule().name(),
                    record.riskBand().name(),
                    record.evidenceProfile().name(),
                    record.hardConstraintFamily().name(),
                    json,
                    record.createdAt(),
                    record.executionMode().name(),
                    record.authorityAlgorithmVersion().orElse(null),
                    record.canaryBucket().isPresent() ? record.canaryBucket().getAsInt() : null,
                    record.authorityApplied(),
                    record.appliedStatus().map(Enum::name).orElse(null));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize decision audit snapshot", ex);
        }
    }

    public DecisionAuditRecord toDomain(DecisionAuditEntity entity) {
        Objects.requireNonNull(entity, "entity");
        try {
            JsonNode root = objectMapper.readTree(entity.getSnapshotJson());
            DecisionAuditRecord record = fromSnapshotNode(root, entity.getCreatedAt());
            return overlayEntityAuthorityFields(record, entity);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize decision audit snapshot", ex);
        }
    }

    private ObjectNode toSnapshotNode(DecisionAuditRecord record) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", DecisionAuditSnapshotSchema.V1);
        root.put("auditId", record.auditId().toString());
        root.put("parkingSpotId", record.parkingSpotId().toString());
        root.put("evaluationId", record.evaluationId().toString());
        root.put("policyVersion", record.policyVersion());
        root.put("decisionEngineVersion", record.decisionEngineVersion());
        root.put("shadowModeVersion", record.shadowModeVersion());
        root.put("evaluatedAt", record.evaluatedAt().toString());
        root.set("evaluationContext", writeContext(record.evaluationContext()));
        root.set("evidence", writeEvidence(record.evidence()));
        root.set("decision", writeDecision(record.decision()));
        root.set("legacyOutcome", writeLegacy(record.legacyOutcome()));
        root.put("comparisonCategory", record.comparisonCategory().name());
        root.put("riskBand", record.riskBand().name());
        root.put("hardConstraintFamily", record.hardConstraintFamily().name());
        root.put("evidenceProfile", record.evidenceProfile().name());
        root.put("decisiveRule", record.decisiveRule().name());
        root.put("createdAt", record.createdAt().toString());
        root.put("executionMode", record.executionMode().name());
        record.authorityAlgorithmVersion().ifPresent(v -> root.put("authorityAlgorithmVersion", v));
        record.canaryBucket().ifPresent(b -> root.put("canaryBucket", b));
        root.put("authorityApplied", record.authorityApplied());
        record.appliedStatus().ifPresent(s -> root.put("appliedStatus", s.name()));
        return root;
    }

    private DecisionAuditRecord fromSnapshotNode(JsonNode root, Instant entityCreatedAt) {
        requireSchema(root);
        UUID auditId = UUID.fromString(text(root, "auditId"));
        UUID parkingSpotId = UUID.fromString(text(root, "parkingSpotId"));
        UUID evaluationId = UUID.fromString(text(root, "evaluationId"));
        String policyVersion = text(root, "policyVersion");
        String engineVersion = text(root, "decisionEngineVersion");
        String shadowVersion = text(root, "shadowModeVersion");
        Instant evaluatedAt = Instant.parse(text(root, "evaluatedAt"));
        EvaluationContext context = readContext(root.get("evaluationContext"));
        EvidenceVector evidence = readEvidence(root.get("evidence"));
        DecisionResult decision = readDecision(root.get("decision"));
        LegacyPublicationOutcome legacy = readLegacy(root.get("legacyOutcome"));
        Instant createdAt = root.hasNonNull("createdAt")
                ? Instant.parse(text(root, "createdAt"))
                : entityCreatedAt;
        DecisionExecutionMode mode = root.hasNonNull("executionMode")
                ? DecisionExecutionMode.valueOf(text(root, "executionMode"))
                : DecisionExecutionMode.SHADOW;
        String algorithm = root.hasNonNull("authorityAlgorithmVersion")
                ? text(root, "authorityAlgorithmVersion")
                : null;
        Integer bucket = root.hasNonNull("canaryBucket") ? root.get("canaryBucket").asInt() : null;
        boolean applied = root.hasNonNull("authorityApplied") && root.get("authorityApplied").asBoolean();
        ParkingSpotStatus appliedStatus = root.hasNonNull("appliedStatus")
                ? ParkingSpotStatus.valueOf(text(root, "appliedStatus"))
                : null;
        return DecisionAuditRecord.of(
                auditId,
                parkingSpotId,
                evaluationId,
                policyVersion,
                engineVersion,
                shadowVersion,
                evaluatedAt,
                evidence,
                context,
                decision,
                legacy,
                ShadowComparisonCategory.valueOf(text(root, "comparisonCategory")),
                RiskBand.valueOf(text(root, "riskBand")),
                HardConstraintFamily.valueOf(text(root, "hardConstraintFamily")),
                EvidenceAvailabilityProfile.valueOf(text(root, "evidenceProfile")),
                DecisivePolicyRule.valueOf(text(root, "decisiveRule")),
                createdAt,
                mode,
                algorithm,
                bucket,
                applied,
                appliedStatus);
    }


    /**
     * Relational authority columns are the query-dimension source of truth for
     * WP-05.8 fields; overlay them onto the deserialized snapshot so V20 rows
     * (DEFAULT SHADOW) remain readable when JSON lacks the new keys.
     */
    private DecisionAuditRecord overlayEntityAuthorityFields(
            DecisionAuditRecord record, DecisionAuditEntity entity) {
        DecisionExecutionMode mode = entity.getExecutionMode() != null && !entity.getExecutionMode().isBlank()
                ? DecisionExecutionMode.valueOf(entity.getExecutionMode())
                : record.executionMode();
        String algorithm = entity.getAuthorityAlgorithmVersion() != null
                ? entity.getAuthorityAlgorithmVersion()
                : record.authorityAlgorithmVersion().orElse(null);
        Integer bucket = entity.getCanaryBucket() != null
                ? entity.getCanaryBucket()
                : (record.canaryBucket().isPresent() ? record.canaryBucket().getAsInt() : null);
        boolean applied = entity.isAuthorityApplied();
        ParkingSpotStatus appliedStatus = entity.getAppliedStatus() != null && !entity.getAppliedStatus().isBlank()
                ? ParkingSpotStatus.valueOf(entity.getAppliedStatus())
                : record.appliedStatus().orElse(null);
        return DecisionAuditRecord.of(
                record.auditId(),
                record.parkingSpotId(),
                record.evaluationId(),
                record.policyVersion(),
                record.decisionEngineVersion(),
                record.shadowModeVersion(),
                record.evaluatedAt(),
                record.evidence(),
                record.evaluationContext(),
                record.decision(),
                record.legacyOutcome(),
                record.comparisonCategory(),
                record.riskBand(),
                record.hardConstraintFamily(),
                record.evidenceProfile(),
                record.decisiveRule(),
                record.createdAt(),
                mode,
                algorithm,
                bucket,
                applied,
                appliedStatus);
    }
    private ObjectNode writeContext(EvaluationContext context) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("evaluationPolicyVersion", context.evaluationPolicyVersion().value());
        node.put("evaluatedAt", context.evaluatedAt().toString());
        context.scenarioKey().ifPresent(key -> node.put("scenarioKey", key));
        return node;
    }

    private EvaluationContext readContext(JsonNode node) {
        AssessmentVersion version = AssessmentVersion.of(text(node, "evaluationPolicyVersion"));
        Instant evaluatedAt = Instant.parse(text(node, "evaluatedAt"));
        if (node.hasNonNull("scenarioKey")) {
            return EvaluationContext.of(version, evaluatedAt, text(node, "scenarioKey"));
        }
        return EvaluationContext.of(version, evaluatedAt);
    }

    private ObjectNode writeEvidence(EvidenceVector evidence) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("parkingSpotId", evidence.parkingSpotId().toString());
        node.put("evaluationId", evidence.evaluationId().toString());
        node.put("collectedAt", evidence.collectedAt().toString());
        node.put("schemaVersion", evidence.schemaVersion());
        ArrayNode items = objectMapper.createArrayNode();
        for (EvidenceItem item : evidence.items()) {
            items.add(writeEvidenceItem(item));
        }
        node.set("items", items);
        return node;
    }

    private EvidenceVector readEvidence(JsonNode node) {
        List<EvidenceItem> items = new ArrayList<>();
        for (JsonNode itemNode : node.get("items")) {
            items.add(readEvidenceItem(itemNode));
        }
        return EvidenceVector.of(
                UUID.fromString(text(node, "parkingSpotId")),
                UUID.fromString(text(node, "evaluationId")),
                Instant.parse(text(node, "collectedAt")),
                text(node, "schemaVersion"),
                items);
    }

    private ObjectNode writeEvidenceItem(EvidenceItem item) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", item.type().name());
        node.put("source", item.source().name());
        node.put("polarity", item.polarity().name());
        node.put("strength", item.strength());
        node.put("observedAt", item.observedAt().toString());
        item.reasonCode().ifPresent(code -> node.put("reasonCode", code.value()));
        item.sourceReference().ifPresent(ref -> node.put("sourceReference", ref));
        return node;
    }

    private EvidenceItem readEvidenceItem(JsonNode node) {
        ReasonCode reason = node.hasNonNull("reasonCode") ? ReasonCode.of(text(node, "reasonCode")) : null;
        String sourceReference = node.hasNonNull("sourceReference") ? text(node, "sourceReference") : null;
        return EvidenceItem.of(
                EvidenceType.valueOf(text(node, "type")),
                EvidenceSource.valueOf(text(node, "source")),
                EvidencePolarity.valueOf(text(node, "polarity")),
                node.get("strength").asInt(),
                Instant.parse(text(node, "observedAt")),
                reason,
                sourceReference);
    }

    private ObjectNode writeDecision(DecisionResult decision) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("parkingSpotId", decision.parkingSpotId().toString());
        node.put("evaluationId", decision.evaluationId().toString());
        node.put("disposition", decision.disposition().name());
        node.set("assessment", writeDerived(decision.assessment()));
        node.set("reasonCodes", writeReasons(decision.reasonCodes()));
        node.put("decisiveRule", decision.decisiveRule().name());
        node.put("policyVersion", decision.policyVersion());
        node.put("decidedAt", decision.decidedAt().toString());
        node.put("asynchronousFollowUpRequired", decision.asynchronousFollowUpRequired());
        return node;
    }

    private DecisionResult readDecision(JsonNode node) {
        return DecisionResult.of(
                UUID.fromString(text(node, "parkingSpotId")),
                UUID.fromString(text(node, "evaluationId")),
                PublicationDisposition.valueOf(text(node, "disposition")),
                readDerived(node.get("assessment")),
                readReasons(node.get("reasonCodes")),
                DecisivePolicyRule.valueOf(text(node, "decisiveRule")),
                text(node, "policyVersion"),
                Instant.parse(text(node, "decidedAt")),
                node.get("asynchronousFollowUpRequired").asBoolean());
    }

    private ObjectNode writeDerived(DerivedAssessment assessment) {
        ObjectNode node = objectMapper.createObjectNode();
        assessment.assessmentBundle().ifPresent(bundle -> node.set("assessmentBundle", writeBundle(bundle)));
        assessment.riskAssessment().ifPresent(risk -> node.set("riskAssessment", writeRisk(risk)));
        node.set("reasonCodes", writeReasons(assessment.reasonCodes()));
        node.put("version", assessment.version().value());
        node.put("evaluatedAt", assessment.evaluatedAt().toString());
        return node;
    }

    private DerivedAssessment readDerived(JsonNode node) {
        Optional<AssessmentBundle> bundle = node.hasNonNull("assessmentBundle")
                ? Optional.of(readBundle(node.get("assessmentBundle")))
                : Optional.empty();
        Optional<RiskAssessment> risk = node.hasNonNull("riskAssessment")
                ? Optional.of(readRisk(node.get("riskAssessment")))
                : Optional.empty();
        return DerivedAssessment.of(
                bundle,
                risk,
                readReasons(node.get("reasonCodes")),
                AssessmentVersion.of(text(node, "version")),
                Instant.parse(text(node, "evaluatedAt")));
    }

    private ObjectNode writeBundle(AssessmentBundle bundle) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("parkingSpotId", bundle.parkingSpotId().toString());
        node.put("evaluationId", bundle.evaluationId().toString());
        node.put("evidenceSchemaVersion", bundle.evidenceSchemaVersion());
        ArrayNode assessments = objectMapper.createArrayNode();
        for (DomainAssessment assessment : bundle.assessments()) {
            assessments.add(writeDomainAssessment(assessment));
        }
        node.set("assessments", assessments);
        node.put("evaluationPolicyVersion", bundle.evaluationPolicyVersion().value());
        node.put("evaluatedAt", bundle.evaluatedAt().toString());
        node.set("globalReasonCodes", writeReasons(bundle.globalReasonCodes()));
        bundle.aggregateEvidenceScore()
                .ifPresent(score -> node.put("aggregateEvidenceScore", score.value()));
        return node;
    }

    private AssessmentBundle readBundle(JsonNode node) {
        List<DomainAssessment> assessments = new ArrayList<>();
        for (JsonNode assessmentNode : node.get("assessments")) {
            assessments.add(readDomainAssessment(assessmentNode));
        }
        Optional<EvidenceScore> aggregate = node.hasNonNull("aggregateEvidenceScore")
                ? Optional.of(EvidenceScore.of(node.get("aggregateEvidenceScore").asInt()))
                : Optional.empty();
        return AssessmentBundle.of(
                UUID.fromString(text(node, "parkingSpotId")),
                UUID.fromString(text(node, "evaluationId")),
                text(node, "evidenceSchemaVersion"),
                assessments,
                AssessmentVersion.of(text(node, "evaluationPolicyVersion")),
                Instant.parse(text(node, "evaluatedAt")),
                readReasons(node.get("globalReasonCodes")),
                aggregate);
    }

    private ObjectNode writeDomainAssessment(DomainAssessment assessment) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("category", assessment.category().name());
        node.put("level", assessment.level().name());
        assessment.categoryScore().ifPresent(score -> node.put("categoryScore", score));
        assessment.confidence().ifPresent(score -> node.put("confidence", score));
        node.put("completeness", assessment.completeness().name());
        node.put("hardConstraint", assessment.hardConstraint());
        node.set("reasonCodes", writeReasons(assessment.reasonCodes()));
        node.set("evidenceReferences", writeReferences(assessment.evidenceReferences()));
        node.put("version", assessment.version().value());
        node.put("evaluatedAt", assessment.evaluatedAt().toString());
        return node;
    }

    private DomainAssessment readDomainAssessment(JsonNode node) {
        OptionalInt categoryScore = node.hasNonNull("categoryScore")
                ? OptionalInt.of(node.get("categoryScore").asInt())
                : OptionalInt.empty();
        OptionalInt confidence = node.hasNonNull("confidence")
                ? OptionalInt.of(node.get("confidence").asInt())
                : OptionalInt.empty();
        return DomainAssessment.of(
                AssessmentCategory.valueOf(text(node, "category")),
                AssessmentLevel.valueOf(text(node, "level")),
                categoryScore,
                confidence,
                AssessmentCompleteness.valueOf(text(node, "completeness")),
                node.get("hardConstraint").asBoolean(),
                readReasons(node.get("reasonCodes")),
                readReferences(node.get("evidenceReferences")),
                AssessmentVersion.of(text(node, "version")),
                Instant.parse(text(node, "evaluatedAt")));
    }

    private ObjectNode writeRisk(RiskAssessment risk) {
        ObjectNode node = objectMapper.createObjectNode();
        risk.score().ifPresent(score -> node.put("score", score.value()));
        node.set("reasonCodes", writeReasons(risk.reasonCodes()));
        node.put("version", risk.version().value());
        node.put("evaluatedAt", risk.evaluatedAt().toString());
        node.put("hardConstraintActive", risk.hardConstraintActive());
        node.set("contributingEvidence", writeReferences(risk.contributingEvidence()));
        return node;
    }

    private RiskAssessment readRisk(JsonNode node) {
        Optional<RiskScore> score = node.hasNonNull("score")
                ? Optional.of(RiskScore.of(node.get("score").asInt()))
                : Optional.empty();
        return RiskAssessment.of(
                score,
                readReasons(node.get("reasonCodes")),
                AssessmentVersion.of(text(node, "version")),
                Instant.parse(text(node, "evaluatedAt")),
                node.get("hardConstraintActive").asBoolean(),
                readReferences(node.get("contributingEvidence")));
    }

    private ObjectNode writeLegacy(LegacyPublicationOutcome legacy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("previousStatus", legacy.previousStatus().name());
        node.put("resultingStatus", legacy.resultingStatus().name());
        node.put("kind", legacy.kind().name());
        return node;
    }

    private LegacyPublicationOutcome readLegacy(JsonNode node) {
        return new LegacyPublicationOutcome(
                ParkingSpotStatus.valueOf(text(node, "previousStatus")),
                ParkingSpotStatus.valueOf(text(node, "resultingStatus")),
                LegacyPublicationOutcome.Kind.valueOf(text(node, "kind")));
    }

    private ArrayNode writeReasons(List<ReasonCode> reasons) {
        ArrayNode array = objectMapper.createArrayNode();
        for (ReasonCode reason : reasons) {
            array.add(reason.value());
        }
        return array;
    }

    private List<ReasonCode> readReasons(JsonNode node) {
        List<ReasonCode> reasons = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return reasons;
        }
        for (JsonNode reasonNode : node) {
            reasons.add(ReasonCode.of(reasonNode.asText()));
        }
        return reasons;
    }

    private ArrayNode writeReferences(List<EvidenceReference> refs) {
        ArrayNode array = objectMapper.createArrayNode();
        for (EvidenceReference ref : refs) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("canonicalKey", ref.canonicalKey());
            node.put("type", ref.type().name());
            node.put("source", ref.source().name());
            ref.reasonCode().ifPresent(code -> node.put("reasonCode", code.value()));
            array.add(node);
        }
        return array;
    }

    private List<EvidenceReference> readReferences(JsonNode node) {
        List<EvidenceReference> refs = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return refs;
        }
        for (JsonNode refNode : node) {
            ReasonCode reason = refNode.hasNonNull("reasonCode")
                    ? ReasonCode.of(text(refNode, "reasonCode"))
                    : null;
            refs.add(EvidenceReference.of(
                    text(refNode, "canonicalKey"),
                    EvidenceType.valueOf(text(refNode, "type")),
                    EvidenceSource.valueOf(text(refNode, "source")),
                    reason));
        }
        return refs;
    }

    private static void requireSchema(JsonNode root) {
        String schema = text(root, "schemaVersion");
        if (!DecisionAuditSnapshotSchema.V1.equals(schema)) {
            throw new IllegalStateException("Unsupported decision audit snapshot schema: " + schema);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException("Missing snapshot field: " + field);
        }
        return value.asText();
    }
}
