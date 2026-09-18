package com.parkio.parking.presentation.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.RegistryPersistencePort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RegistryLinkCandidateResponse(
        UUID id,
        UUID facilityAId,
        UUID facilityBId,
        String sourceKeyA,
        String externalIdA,
        String sourceKeyB,
        String externalIdB,
        String sourceFamilyPair,
        Map<String, Object> evidence,
        Map<String, Object> scoreComponents,
        List<String> hardConflicts,
        double totalScore,
        Instant generatedAt,
        String reviewState,
        String algorithmVersion,
        long version) {

    public static RegistryLinkCandidateResponse from(
            RegistryPersistencePort.Candidate candidate, ObjectMapper mapper) {
        return new RegistryLinkCandidateResponse(
                candidate.id(),
                candidate.facilityAId(),
                candidate.facilityBId(),
                candidate.sourceKeyA(),
                candidate.externalIdA(),
                candidate.sourceKeyB(),
                candidate.externalIdB(),
                candidate.sourceFamilyPair(),
                readMap(candidate.evidenceJson(), mapper),
                readMap(candidate.scoreJson(), mapper),
                readList(candidate.hardConflictsJson(), mapper),
                candidate.totalScore(),
                candidate.generatedAt(),
                candidate.reviewState(),
                candidate.algorithmVersion(),
                candidate.version());
    }

    private static Map<String, Object> readMap(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored candidate evidence is invalid", ex);
        }
    }

    private static List<String> readList(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored candidate conflicts are invalid", ex);
        }
    }
}
