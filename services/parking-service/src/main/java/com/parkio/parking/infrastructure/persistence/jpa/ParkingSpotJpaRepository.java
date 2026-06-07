package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.infrastructure.persistence.entity.ParkingSpotEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingSpotJpaRepository extends JpaRepository<ParkingSpotEntity, UUID> {

    List<ParkingSpotEntity> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

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
