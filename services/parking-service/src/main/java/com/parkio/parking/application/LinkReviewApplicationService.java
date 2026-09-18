package com.parkio.parking.application;

import com.parkio.parking.application.port.RegistryPersistencePort;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkReviewApplicationService {
    private static final Set<String> REVIEW_STATES =
            Set.of("PENDING", "ACCEPTED", "REJECTED", "DISTINCT", "REOPENED");

    private final RegistryProperties properties;
    private final RegistryPersistencePort persistence;
    private final RegistryMetrics metrics;
    private final Clock clock;

    public LinkReviewApplicationService(
            RegistryProperties properties,
            RegistryPersistencePort persistence,
            RegistryMetrics metrics,
            Clock clock) {
        this.properties = properties;
        this.persistence = persistence;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RegistryPersistencePort.CandidatePage pending(int page, int size, String state) {
        requireReviewApi();
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
        String normalizedState = state == null ? "PENDING" : state.toUpperCase(Locale.ROOT);
        if (!REVIEW_STATES.contains(normalizedState)) {
            throw new IllegalArgumentException("Unsupported review state");
        }
        return persistence.findByState(normalizedState, page, size);
    }

    @Transactional(readOnly = true)
    public Optional<RegistryPersistencePort.Candidate> detail(UUID id) {
        requireReviewApi();
        return persistence.findCandidate(id);
    }

    @Transactional
    public RegistryPersistencePort.Candidate accept(
            UUID id, long expectedVersion, UUID chosenFacilityId, String reviewer) {
        requireReviewedLinking();
        RegistryPersistencePort.Candidate candidate = requireCandidate(id);
        if (hasHardConflicts(candidate.hardConflictsJson())) {
            throw new IllegalStateException("Hard-conflict municipal registry candidates cannot be accepted");
        }
        if ("ACCEPTED".equals(candidate.reviewState())
                && chosenFacilityId.equals(candidate.chosenFacilityId())) {
            return candidate;
        }
        if (!chosenFacilityId.equals(candidate.facilityAId())
                && !chosenFacilityId.equals(candidate.facilityBId())) {
            throw new IllegalArgumentException("chosenFacilityId must be one of the candidate facilities");
        }
        Instant now = clock.instant();
        RegistryPersistencePort.Candidate updated = persistence.review(
                id, expectedVersion, "ACCEPTED", requireReviewer(reviewer),
                "reviewed_multi_signal_link", chosenFacilityId, now);
        persistence.attachSourceLinksAndSupersede(candidate, chosenFacilityId, reviewer, now);
        metrics.review(candidate.sourceFamilyPair(), "ACCEPTED", candidate.algorithmVersion());
        return updated;
    }

    @Transactional
    public RegistryPersistencePort.Candidate reject(
            UUID id, long expectedVersion, String reason, String reviewer) {
        return decideWithoutLink(id, expectedVersion, "REJECTED", reason, reviewer);
    }

    @Transactional
    public RegistryPersistencePort.Candidate distinct(
            UUID id, long expectedVersion, String reason, String reviewer) {
        return decideWithoutLink(id, expectedVersion, "DISTINCT", reason, reviewer);
    }

    @Transactional
    public RegistryPersistencePort.Candidate reopen(
            UUID id, long expectedVersion, String reason, String reviewer) {
        requireReviewedLinking();
        RegistryPersistencePort.Candidate candidate = requireCandidate(id);
        if ("REOPENED".equals(candidate.reviewState())) {
            return candidate;
        }
        Instant now = clock.instant();
        if ("ACCEPTED".equals(candidate.reviewState())) {
            persistence.reopenLink(candidate, reviewer, now);
        }
        RegistryPersistencePort.Candidate updated = persistence.review(
                id, expectedVersion, "REOPENED", requireReviewer(reviewer),
                boundedReason(reason), null, now);
        metrics.review(candidate.sourceFamilyPair(), "REOPENED", candidate.algorithmVersion());
        return updated;
    }

    private RegistryPersistencePort.Candidate decideWithoutLink(
            UUID id, long expectedVersion, String state, String reason, String reviewer) {
        requireReviewedLinking();
        RegistryPersistencePort.Candidate candidate = requireCandidate(id);
        if (state.equals(candidate.reviewState())) {
            return candidate;
        }
        RegistryPersistencePort.Candidate updated = persistence.review(
                id, expectedVersion, state, requireReviewer(reviewer),
                boundedReason(reason), null, clock.instant());
        metrics.review(candidate.sourceFamilyPair(), state, candidate.algorithmVersion());
        return updated;
    }

    private RegistryPersistencePort.Candidate requireCandidate(UUID id) {
        return persistence.findCandidate(id)
                .orElseThrow(() -> new CandidateNotFoundException(id));
    }

    private void requireReviewApi() {
        if (!properties.isReviewApiEnabled()) {
            throw new RegistryApiDisabledException();
        }
    }

    private void requireReviewedLinking() {
        requireReviewApi();
        if (!properties.isReviewedLinkingEnabled()) {
            throw new IllegalStateException("Reviewed municipal registry linking is disabled");
        }
        if (properties.isAutomaticLinkingEnabled()) {
            throw new IllegalStateException("Automatic municipal registry linking is prohibited");
        }
    }

    private static String boundedReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 512) {
            throw new IllegalArgumentException("reason is required and must not exceed 512 characters");
        }
        return reason;
    }

    private static String requireReviewer(String reviewer) {
        if (reviewer == null || reviewer.isBlank() || reviewer.length() > 128) {
            throw new IllegalArgumentException("reviewer is required");
        }
        return reviewer;
    }

    private static boolean hasHardConflicts(String hardConflictsJson) {
        if (hardConflictsJson == null || hardConflictsJson.isBlank()) {
            return false;
        }
        return !"[]".equals(hardConflictsJson.replaceAll("\\s", ""));
    }

    public static final class RegistryApiDisabledException extends RuntimeException {}

    public static final class CandidateNotFoundException extends RuntimeException {
        private final UUID id;

        public CandidateNotFoundException(UUID id) {
            super("Registry link candidate not found");
            this.id = id;
        }

        public UUID id() {
            return id;
        }
    }
}
