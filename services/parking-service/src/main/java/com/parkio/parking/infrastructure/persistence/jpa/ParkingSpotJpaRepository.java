package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingSpotJpaRepository extends JpaRepository<ParkingSpotEntity, UUID> {

    List<ParkingSpotEntity> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

    /** Per-status spot count for the {@code parkio.parking.*.count} gauges (cheap COUNT). */
    long countByStatus(ParkingSpotStatus status);

    /**
     * Expiry candidates. The status filter is the scheduler's half of the "pending spots
     * never expire" rule — {@code PENDING_VALIDATION} / {@code PENDING_REVIEW} are absent
     * by construction, and the domain enforces the same rule on every other path.
     */
    @Query(value = """
            SELECT * FROM parking_spots
            WHERE status IN ('ACTIVE', 'VERIFIED', 'SUSPICIOUS')
              AND expires_at < :now
            ORDER BY expires_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ParkingSpotEntity> findExpiredCandidates(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    /**
     * Spots whose moderation deadline elapsed while still pending, claimed in bounded
     * lock-safe batches. Backed by {@code idx_parking_spots_moderation_deadline}.
     */
    @Query(value = """
            SELECT * FROM parking_spots
            WHERE status IN ('PENDING_VALIDATION', 'PENDING_REVIEW')
              AND moderation_deadline_at < :now
            ORDER BY moderation_deadline_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ParkingSpotEntity> findModerationTimeoutCandidates(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    /**
     * Invariant counter for {@code parkio.parking.expired_before_approved.count}: spots that
     * reached a terminal expiry without ever having been published. Correct behaviour keeps
     * this at zero — a non-zero reading means a spot's lifetime was consumed by moderation.
     */
    long countByStatusAndActivatedAtIsNull(ParkingSpotStatus status);

    /** Oldest still-pending submission, for the moderation-backlog age gauge. */
    @Query("SELECT MIN(s.createdAt) FROM ParkingSpotEntity s WHERE s.status IN :statuses")
    Instant findOldestPendingCreatedAt(@Param("statuses") Collection<ParkingSpotStatus> statuses);

    /**
     * PostGIS radius search, nearest first. Pre-filters by visibility for index
     * efficiency; the application layer still enforces visibility authoritatively.
     * Native query — only runs against PostGIS (production), never H2 tests.
     */
    @Query(value = """
            SELECT * FROM parking_spots
            WHERE status IN ('ACTIVE', 'VERIFIED')
              AND legal_status <> 'ILLEGAL_OR_RISKY'
              AND expires_at > now()
              AND ST_DWithin(location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusMeters)
            ORDER BY location <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
            LIMIT :resultLimit
            """, nativeQuery = true)
    List<ParkingSpotEntity> findNearby(@Param("lat") double latitude,
                                       @Param("lng") double longitude,
                                       @Param("radiusMeters") double radiusMeters,
                                       @Param("resultLimit") int resultLimit);
}
