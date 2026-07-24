package com.parkio.parking.infrastructure.persistence.jpa;

import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingSessionJpaRepository extends JpaRepository<ParkingSession, UUID> {

    Optional<ParkingSession> findByUserIdAndStatus(UUID userId, ParkingSessionStatus status);

    Optional<ParkingSession> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
            SELECT parkingSession.status
            FROM ParkingSession parkingSession
            WHERE parkingSession.id = :id
              AND parkingSession.userId = :userId
            """)
    Optional<ParkingSessionStatus> findStatusByIdAndUserId(
            @Param("id") UUID id, @Param("userId") UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM ParkingSession parkingSession
            WHERE parkingSession.id = :id
              AND parkingSession.userId = :userId
              AND parkingSession.status IN (
                  com.parkio.parking.domain.ParkingSessionStatus.COMPLETED,
                  com.parkio.parking.domain.ParkingSessionStatus.CANCELLED
              )
            """)
    int deleteTerminalByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM ParkingSession parkingSession
            WHERE parkingSession.userId = :userId
              AND parkingSession.status IN (
                  com.parkio.parking.domain.ParkingSessionStatus.COMPLETED,
                  com.parkio.parking.domain.ParkingSessionStatus.CANCELLED
              )
            """)
    int deleteAllTerminalByUserId(@Param("userId") UUID userId);

    @Query("""
            SELECT parkingSession
            FROM ParkingSession parkingSession
            WHERE parkingSession.userId = :userId
              AND parkingSession.status IN (
                  com.parkio.parking.domain.ParkingSessionStatus.COMPLETED,
                  com.parkio.parking.domain.ParkingSessionStatus.CANCELLED
              )
            ORDER BY parkingSession.startedAt DESC, parkingSession.id DESC
            """)
    Slice<ParkingSession> findHistoryByUserId(
            @Param("userId") UUID userId, Pageable pageable);

    @Query("""
            SELECT parkingSession
            FROM ParkingSession parkingSession
            WHERE parkingSession.userId = :userId
              AND parkingSession.status IN (
                  com.parkio.parking.domain.ParkingSessionStatus.COMPLETED,
                  com.parkio.parking.domain.ParkingSessionStatus.CANCELLED
              )
              AND (
                  parkingSession.startedAt < :cursorStartedAt
                  OR (
                      parkingSession.startedAt = :cursorStartedAt
                      AND parkingSession.id < :cursorId
                  )
              )
            ORDER BY parkingSession.startedAt DESC, parkingSession.id DESC
            """)
    Slice<ParkingSession> findHistoryByUserIdAfter(
            @Param("userId") UUID userId,
            @Param("cursorStartedAt") Instant cursorStartedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
