package com.parkio.parking.application.port;

import com.parkio.parking.externalsource.registry.FieldProvenanceSelection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegistryPersistencePort {
    record CandidateDraft(
            UUID facilityAId,
            UUID facilityBId,
            String sourceKeyA,
            String externalIdA,
            String sourceKeyB,
            String externalIdB,
            String sourceFamilyPair,
            String evidenceJson,
            String scoreJson,
            double totalScore,
            String hardConflictsJson,
            Instant generatedAt,
            String sourceVersionA,
            String sourceVersionB,
            String algorithmVersion) {}

    record Candidate(
            UUID id,
            UUID facilityAId,
            UUID facilityBId,
            String sourceKeyA,
            String externalIdA,
            String sourceKeyB,
            String externalIdB,
            String sourceFamilyPair,
            String evidenceJson,
            String scoreJson,
            double totalScore,
            String hardConflictsJson,
            Instant generatedAt,
            String sourceVersionA,
            String sourceVersionB,
            String reviewState,
            String reviewedBy,
            Instant decisionTimestamp,
            String rejectionReason,
            UUID chosenFacilityId,
            String algorithmVersion,
            long version) {}

    record CandidatePage(List<Candidate> content, int page, int size, long totalElements) {}

    Optional<Candidate> createCandidateIfAbsent(CandidateDraft draft);

    CandidatePage findByState(String reviewState, int page, int size);

    Optional<Candidate> findCandidate(UUID id);

    Candidate review(
            UUID candidateId,
            long expectedVersion,
            String newState,
            String reviewer,
            String reason,
            UUID chosenFacilityId,
            Instant decisionTimestamp);

    void attachSourceLinksAndSupersede(
            Candidate candidate, UUID chosenFacilityId, String reviewer, Instant now);

    void reopenLink(Candidate candidate, String reviewer, Instant now);

    void upsertProvenance(FieldProvenanceSelection selection);
}
