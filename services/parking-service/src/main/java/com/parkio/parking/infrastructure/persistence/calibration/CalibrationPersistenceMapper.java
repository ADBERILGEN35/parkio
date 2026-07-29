package com.parkio.parking.infrastructure.persistence.calibration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.parkio.parking.calibration.CalibrationAttributionQuality;
import com.parkio.parking.calibration.CalibrationCohortKey;
import com.parkio.parking.calibration.CalibrationEngineType;
import com.parkio.parking.calibration.CalibrationLabel;
import com.parkio.parking.calibration.CalibrationLabelCategory;
import com.parkio.parking.calibration.CalibrationLabelFinality;
import com.parkio.parking.calibration.CalibrationLabelQuality;
import com.parkio.parking.calibration.CalibrationLabelSource;
import com.parkio.parking.calibration.CalibrationMappingVersion;
import com.parkio.parking.calibration.CalibrationMetricApplicability;
import com.parkio.parking.calibration.CalibrationMetricType;
import com.parkio.parking.calibration.CalibrationMetricValue;
import com.parkio.parking.calibration.CalibrationObservation;
import com.parkio.parking.calibration.CalibrationObservationHorizon;
import com.parkio.parking.calibration.CalibrationPolicyConfig;
import com.parkio.parking.calibration.CalibrationPrediction;
import com.parkio.parking.calibration.CalibrationReadinessAssessment;
import com.parkio.parking.calibration.CalibrationReadinessReason;
import com.parkio.parking.calibration.CalibrationReadinessStatus;
import com.parkio.parking.calibration.CalibrationReport;
import com.parkio.parking.calibration.CalibrationReportStatus;
import com.parkio.parking.calibration.CalibrationWindow;
import com.parkio.parking.infrastructure.persistence.entity.CalibrationObservationEntity;
import com.parkio.parking.infrastructure.persistence.entity.CalibrationReadinessEntity;
import com.parkio.parking.infrastructure.persistence.entity.CalibrationReportEntity;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Serializes canonical calibration-domain payloads for append-only persistence. */
public final class CalibrationPersistenceMapper {

    private final ObjectMapper objectMapper;

    public CalibrationPersistenceMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CalibrationObservationEntity toEntity(CalibrationObservation observation) {
        CalibrationPrediction prediction = observation.prediction();
        CalibrationLabel label = observation.label();
        return new CalibrationObservationEntity(
                observation.observationId(),
                observation.observationId(),
                observation.engineType().name(),
                prediction.sourceEvaluationId(),
                label.sourceRecordId(),
                prediction.policyVersion(),
                prediction.schemaVersion(),
                prediction.mappingVersion(),
                prediction.aggregationVersion(),
                CalibrationMappingVersion.V1.value(),
                CalibrationPolicyConfig.POLICY_VERSION,
                observation.horizon().name(),
                observation.cohortKey(),
                observation.attributionQuality().name(),
                label.labelQuality().name(),
                label.labelFinality().name(),
                observation.predictedAt(),
                observation.labeledAt(),
                observation.createdAt(),
                writeJson(writeObservationPayload(observation)));
    }

    public CalibrationObservation toObservationDomain(CalibrationObservationEntity entity) {
        JsonNode payload = readTree(entity.getObservationPayloadJson());
        CalibrationPrediction prediction = readPrediction(payload.get("prediction"), entity);
        CalibrationLabel label = readLabel(payload.get("label"), entity);
        int completeness = payload.path("observationCompletenessBasisPoints").asInt();
        return new CalibrationObservation(
                entity.getObservationId(),
                CalibrationEngineType.valueOf(entity.getEngineType()),
                prediction,
                label,
                CalibrationObservationHorizon.valueOf(entity.getObservationHorizon()),
                entity.getCohortKey(),
                CalibrationAttributionQuality.valueOf(entity.getAttributionQuality()),
                completeness,
                entity.getPredictedAt(),
                entity.getLabeledAt(),
                entity.getCreatedAt());
    }

    public CalibrationReportEntity toEntity(CalibrationReport report, Instant createdAt) {
        return new CalibrationReportEntity(
                report.reportId(),
                report.reportId(),
                report.engineType().name(),
                report.baselinePolicyVersion().orElse(null),
                report.candidatePolicyVersion().orElse(null),
                report.calibrationPolicyVersion(),
                report.window().start(),
                report.window().end(),
                report.cohortKey().canonicalKey(),
                Math.toIntExact(report.observationCount()),
                Math.toIntExact(report.labeledCount()),
                report.reportStatus().name(),
                report.sourceWatermark(),
                report.generatedAt(),
                createdAt,
                writeJson(writeReportPayload(report)));
    }

    public CalibrationReport toReportDomain(CalibrationReportEntity entity) {
        JsonNode payload = readTree(entity.getReportPayloadJson());
        CalibrationWindow window = new CalibrationWindow(entity.getWindowStart(), entity.getWindowEnd());
        CalibrationCohortKey cohortKey = readCohortKey(payload.get("cohortKey"));
        List<CalibrationMetricValue> metrics = readMetrics(payload.withArray("metrics"));
        Optional<String> baseline = Optional.ofNullable(entity.getBaselinePolicyVersion());
        Optional<String> candidate = Optional.ofNullable(entity.getCandidatePolicyVersion());
        return new CalibrationReport(
                entity.getReportId(),
                CalibrationEngineType.valueOf(entity.getEngineType()),
                baseline,
                candidate,
                entity.getCalibrationPolicyVersion(),
                window,
                cohortKey,
                entity.getObservationCount(),
                entity.getLabeledCount(),
                metrics,
                CalibrationReportStatus.valueOf(entity.getReportStatus()),
                entity.getSourceWatermark(),
                entity.getGeneratedAt());
    }

    public CalibrationReadinessEntity toEntity(CalibrationReadinessAssessment assessment, Instant createdAt) {
        return new CalibrationReadinessEntity(
                assessment.assessmentId(),
                assessment.assessmentId(),
                assessment.engineType().name(),
                assessment.policyVersion(),
                assessment.reportId(),
                assessment.readinessStatus().name(),
                assessment.assessedAt(),
                createdAt,
                writeJson(writeReadinessPayload(assessment)));
    }

    public CalibrationReadinessAssessment toReadinessDomain(CalibrationReadinessEntity entity) {
        JsonNode payload = readTree(entity.getReasonPayloadJson());
        List<CalibrationReadinessReason> reasons = new ArrayList<>();
        payload.withArray("reasons").forEach(node -> reasons.add(CalibrationReadinessReason.valueOf(node.asText())));
        return new CalibrationReadinessAssessment(
                entity.getAssessmentId(),
                CalibrationEngineType.valueOf(entity.getEngineType()),
                entity.getPolicyVersion(),
                entity.getCalibrationReportId(),
                CalibrationReadinessStatus.valueOf(entity.getReadinessStatus()),
                reasons,
                entity.getAssessedAt());
    }

    private JsonNode writeObservationPayload(CalibrationObservation observation) {
        var root = objectMapper.createObjectNode();
        root.set("prediction", writePrediction(observation.prediction()));
        root.set("label", writeLabel(observation.label()));
        root.put("observationCompletenessBasisPoints", observation.observationCompletenessBasisPoints());
        return root;
    }

    private JsonNode writePrediction(CalibrationPrediction prediction) {
        var node = objectMapper.createObjectNode();
        node.put("engineType", prediction.engineType().name());
        node.put("policyVersion", prediction.policyVersion());
        node.put("schemaVersion", prediction.schemaVersion());
        node.put("mappingVersion", prediction.mappingVersion());
        node.put("aggregationVersion", prediction.aggregationVersion());
        node.put("predictedBand", prediction.predictedBand());
        node.put("predictedCategory", prediction.predictedCategory());
        node.put("sourceEvaluationId", prediction.sourceEvaluationId().toString());
        return node;
    }

    private JsonNode writeLabel(CalibrationLabel label) {
        var node = objectMapper.createObjectNode();
        node.put("labelCategory", label.labelCategory().name());
        node.put("labelSource", label.labelSource().name());
        node.put("labelQuality", label.labelQuality().name());
        node.put("labelFinality", label.labelFinality().name());
        node.put("sourceRecordId", label.sourceRecordId().toString());
        node.put("labeledAt", label.labeledAt().toString());
        return node;
    }

    private JsonNode writeReportPayload(CalibrationReport report) {
        var root = objectMapper.createObjectNode();
        root.set("cohortKey", writeCohortKey(report.cohortKey()));
        ArrayNode metrics = root.putArray("metrics");
        for (CalibrationMetricValue metric : report.metrics()) {
            metrics.add(writeMetric(metric));
        }
        return root;
    }

    private JsonNode writeCohortKey(CalibrationCohortKey cohortKey) {
        var node = objectMapper.createObjectNode();
        node.put("engineType", cohortKey.engineType().name());
        node.put("policyVersion", cohortKey.policyVersion());
        node.put("predictionBand", cohortKey.predictionBand());
        cohortKey.labelCategory().ifPresentOrElse(
                category -> node.put("labelCategory", category.name()),
                () -> node.putNull("labelCategory"));
        node.put("horizon", cohortKey.horizon().name());
        return node;
    }

    private JsonNode writeMetric(CalibrationMetricValue metric) {
        var node = objectMapper.createObjectNode();
        node.put("type", metric.type().name());
        node.put("applicability", metric.applicability().name());
        node.put("numerator", metric.numerator());
        node.put("denominator", metric.denominator());
        metric.ratioBasisPoints().ifPresentOrElse(
                value -> node.put("ratioBasisPoints", value),
                () -> node.putNull("ratioBasisPoints"));
        metric.notes().ifPresentOrElse(
                notes -> node.put("notes", notes),
                () -> node.putNull("notes"));
        return node;
    }

    private JsonNode writeReadinessPayload(CalibrationReadinessAssessment assessment) {
        var root = objectMapper.createObjectNode();
        ArrayNode reasons = root.putArray("reasons");
        assessment.reasons().forEach(reason -> reasons.add(reason.name()));
        return root;
    }

    private CalibrationPrediction readPrediction(JsonNode node, CalibrationObservationEntity entity) {
        if (node != null && !node.isMissingNode()) {
            return new CalibrationPrediction(
                    CalibrationEngineType.valueOf(node.get("engineType").asText()),
                    node.get("policyVersion").asText(),
                    node.get("schemaVersion").asText(),
                    node.get("mappingVersion").asText(),
                    node.get("aggregationVersion").asText(),
                    node.get("predictedBand").asText(),
                    node.get("predictedCategory").asText(),
                    UUID.fromString(node.get("sourceEvaluationId").asText()));
        }
        return new CalibrationPrediction(
                CalibrationEngineType.valueOf(entity.getEngineType()),
                entity.getPolicyVersion(),
                entity.getSchemaVersion(),
                entity.getMappingVersion(),
                entity.getAggregationVersion(),
                "UNKNOWN",
                "UNKNOWN",
                entity.getSourceEvaluationId());
    }

    private CalibrationLabel readLabel(JsonNode node, CalibrationObservationEntity entity) {
        if (node != null && !node.isMissingNode()) {
            return new CalibrationLabel(
                    CalibrationLabelCategory.valueOf(node.get("labelCategory").asText()),
                    CalibrationLabelSource.valueOf(node.get("labelSource").asText()),
                    CalibrationLabelQuality.valueOf(node.get("labelQuality").asText()),
                    CalibrationLabelFinality.valueOf(node.get("labelFinality").asText()),
                    UUID.fromString(node.get("sourceRecordId").asText()),
                    Instant.parse(node.get("labeledAt").asText()));
        }
        return new CalibrationLabel(
                CalibrationLabelCategory.UNKNOWN,
                CalibrationLabelSource.NOT_AVAILABLE,
                CalibrationLabelQuality.valueOf(entity.getLabelQuality()),
                CalibrationLabelFinality.valueOf(entity.getLabelFinality()),
                entity.getLabelSourceId(),
                entity.getLabeledAt());
    }

    private CalibrationCohortKey readCohortKey(JsonNode node) {
        Optional<CalibrationLabelCategory> labelCategory = node.get("labelCategory").isNull()
                ? Optional.empty()
                : Optional.of(CalibrationLabelCategory.valueOf(node.get("labelCategory").asText()));
        return new CalibrationCohortKey(
                CalibrationEngineType.valueOf(node.get("engineType").asText()),
                node.get("policyVersion").asText(),
                node.get("predictionBand").asText(),
                labelCategory,
                CalibrationObservationHorizon.valueOf(node.get("horizon").asText()));
    }

    private List<CalibrationMetricValue> readMetrics(ArrayNode metricsNode) {
        List<CalibrationMetricValue> metrics = new ArrayList<>();
        for (JsonNode node : metricsNode) {
            OptionalInt ratioBasisPoints = node.get("ratioBasisPoints").isNull()
                    ? OptionalInt.empty()
                    : OptionalInt.of(node.get("ratioBasisPoints").asInt());
            Optional<String> notes = node.get("notes").isNull()
                    ? Optional.empty()
                    : Optional.of(node.get("notes").asText());
            metrics.add(new CalibrationMetricValue(
                    CalibrationMetricType.valueOf(node.get("type").asText()),
                    CalibrationMetricApplicability.valueOf(node.get("applicability").asText()),
                    node.get("numerator").asLong(),
                    node.get("denominator").asLong(),
                    ratioBasisPoints,
                    notes));
        }
        return metrics;
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