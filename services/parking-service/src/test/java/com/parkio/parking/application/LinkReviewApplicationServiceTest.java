package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.application.port.RegistryPersistencePort;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinkReviewApplicationServiceTest {
    private final RegistryPersistencePort persistence = mock(RegistryPersistencePort.class);
    private final RegistryMetrics metrics = mock(RegistryMetrics.class);
    private final RegistryProperties properties = new RegistryProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC);
    private final UUID candidateId = UUID.randomUUID();
    private final UUID facilityA = UUID.randomUUID();
    private final UUID facilityB = UUID.randomUUID();
    private LinkReviewApplicationService service;

    @BeforeEach
    void setUp() {
        properties.setReviewApiEnabled(true);
        properties.setReviewedLinkingEnabled(true);
        service = new LinkReviewApplicationService(properties, persistence, metrics, clock);
    }

    @Test
    void hardConflictCandidateCannotBeAccepted() {
        RegistryPersistencePort.Candidate candidate = candidate("[\"facility_type_exclusive\"]", "PENDING");
        when(persistence.findCandidate(candidateId)).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.accept(candidateId, 0, facilityA, "admin-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Hard-conflict");

        verify(persistence, never()).review(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(persistence, never()).attachSourceLinksAndSupersede(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void hardConflictCandidateMayStillBeRejected() {
        RegistryPersistencePort.Candidate candidate = candidate("[ \"operator_contradiction\" ]", "PENDING");
        RegistryPersistencePort.Candidate rejected = candidate("[\"operator_contradiction\"]", "REJECTED");
        when(persistence.findCandidate(candidateId)).thenReturn(Optional.of(candidate));
        when(persistence.review(candidateId, 0, "REJECTED", "admin-1", "conflicting records", null, clock.instant()))
                .thenReturn(rejected);

        service.reject(candidateId, 0, "conflicting records", "admin-1");

        verify(persistence).review(candidateId, 0, "REJECTED", "admin-1", "conflicting records", null, clock.instant());
    }

    @Test
    void reviewApiAloneDoesNotEnableReviewedLinkApplication() {
        properties.setReviewApiEnabled(true);
        properties.setReviewedLinkingEnabled(false);
        service = new LinkReviewApplicationService(properties, persistence, metrics, clock);
        when(persistence.findCandidate(candidateId)).thenReturn(Optional.of(candidate("[]", "PENDING")));

        assertThatThrownBy(() -> service.accept(candidateId, 0, facilityA, "admin-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
        verify(persistence, never()).attachSourceLinksAndSupersede(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptIsIdempotentWhenAlreadyAcceptedForChosenFacility() {
        RegistryPersistencePort.Candidate accepted = new RegistryPersistencePort.Candidate(
                candidateId, facilityA, facilityB,
                "izmir-izum-otoparklar", "izum-1",
                "osm-geofabrik-turkey", "osm-1",
                "IZUM_OSM", "{}", "{}", 0.8, "[]",
                clock.instant(), "v1", "v1", "ACCEPTED", "admin-1", clock.instant(), null,
                facilityA, LinkCandidateGenerationService.class.getSimpleName(), 1);
        when(persistence.findCandidate(candidateId)).thenReturn(Optional.of(accepted));

        RegistryPersistencePort.Candidate result = service.accept(candidateId, 1, facilityA, "admin-2");

        assertThat(result.reviewState()).isEqualTo("ACCEPTED");
        verify(persistence, never()).review(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(persistence, never()).attachSourceLinksAndSupersede(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private RegistryPersistencePort.Candidate candidate(String hardConflicts, String state) {
        return new RegistryPersistencePort.Candidate(
                candidateId, facilityA, facilityB,
                "izmir-izum-otoparklar", "izum-1",
                "osm-geofabrik-turkey", "osm-1",
                "IZUM_OSM", "{}", "{}", 0.8, hardConflicts,
                clock.instant(), "v1", "v1", state, null, null, null,
                null, LinkCandidateGenerationService.class.getSimpleName(), 0);
    }
}