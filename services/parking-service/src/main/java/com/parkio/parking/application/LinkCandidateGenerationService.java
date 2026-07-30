package com.parkio.parking.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.RegistryPersistencePort;
import com.parkio.parking.externalsource.registry.LinkCandidateEvidence;
import com.parkio.parking.externalsource.registry.LinkCandidatePolicy;
import com.parkio.parking.externalsource.registry.LinkCandidateScore;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkCandidateGenerationService {
    public record GenerationResult(
            boolean eligible,
            boolean inserted,
            UUID candidateId,
            String reasonCategory,
            String reviewState) {}

    private final RegistryProperties properties;
    private final RegistryPersistencePort persistence;
    private final ObjectMapper objectMapper;
    private final RegistryMetrics metrics;
    private final Clock clock;

    public LinkCandidateGenerationService(
            RegistryProperties properties,
            RegistryPersistencePort persistence,
            ObjectMapper objectMapper,
            RegistryMetrics metrics,
            Clock clock) {
        this.properties = properties;
        this.persistence = persistence;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Generates a review candidate only. It never links facilities, writes occupancy,
     * assigns tariffs, or changes public projection.
     */
    @Transactional
    public GenerationResult generate(
            LinkCandidateEvidence evidence, UUID facilityAId, UUID facilityBId) {
        if (!properties.isCandidateGenerationEnabled()) {
            throw new IllegalStateException("Registry candidate generation is disabled");
        }
        LinkCandidateScore score = LinkCandidatePolicy.evaluate(evidence);
        String pair = evidence.sourceFamilyPair();
        if (!score.reviewRequired()) {
            metrics.candidate(pair, "skipped", score.reasonCategory(), LinkCandidatePolicy.ALGORITHM_VERSION);
            return new GenerationResult(false, false, null, score.reasonCategory(), null);
        }

        boolean originalOrder = orderKey(evidence.sourceKeyA(), evidence.externalIdA())
                .compareTo(orderKey(evidence.sourceKeyB(), evidence.externalIdB())) <= 0;
        Instant now = clock.instant();
        RegistryPersistencePort.CandidateDraft draft = new RegistryPersistencePort.CandidateDraft(
                originalOrder ? facilityAId : facilityBId,
                originalOrder ? facilityBId : facilityAId,
                originalOrder ? evidence.sourceKeyA() : evidence.sourceKeyB(),
                originalOrder ? evidence.externalIdA() : evidence.externalIdB(),
                originalOrder ? evidence.sourceKeyB() : evidence.sourceKeyA(),
                originalOrder ? evidence.externalIdB() : evidence.externalIdA(),
                pair,
                json(evidenceMap(evidence, score)),
                json(score.components()),
                score.total(),
                json(score.hardConflicts()),
                now,
                originalOrder ? evidence.sourceVersionA() : evidence.sourceVersionB(),
                originalOrder ? evidence.sourceVersionB() : evidence.sourceVersionA(),
                LinkCandidatePolicy.ALGORITHM_VERSION);
        Optional<RegistryPersistencePort.Candidate> inserted = persistence.createCandidateIfAbsent(draft);
        String outcome = inserted.isPresent() ? "inserted" : "duplicate_suppressed";
        metrics.candidate(pair, outcome, score.reasonCategory(), LinkCandidatePolicy.ALGORITHM_VERSION);
        return new GenerationResult(
                true,
                inserted.isPresent(),
                inserted.map(RegistryPersistencePort.Candidate::id).orElse(null),
                score.reasonCategory(),
                "PENDING");
    }

    private Map<String, Object> evidenceMap(LinkCandidateEvidence evidence, LinkCandidateScore score) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("distanceMeters", evidence.distanceMeters());
        map.put("nameSimilarity", evidence.nameSimilarity());
        map.put("operatorSimilarity", evidence.operatorSimilarity());
        map.put("typeA", evidence.typeA());
        map.put("typeB", evidence.typeB());
        map.put("accessA", evidence.accessA());
        map.put("accessB", evidence.accessB());
        map.put("capacityA", evidence.capacityA());
        map.put("capacityB", evidence.capacityB());
        map.put("addressMatch", evidence.addressMatch());
        map.put("districtMatch", evidence.districtMatch());
        map.put("supportingSignals", score.supportingSignals());
        return map;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Cannot serialize registry candidate evidence", ex);
        }
    }

    private static String orderKey(String sourceKey, String externalId) {
        return sourceKey + "\u0000" + externalId;
    }
}
