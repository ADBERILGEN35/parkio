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

    @Query("""
            SELECT parkingSession
            FROM ParkingSession parkingSession
            WHERE parkingSession.status = com.parkio.parking.domain.ParkingSessionStatus.ACTIVE
              AND parkingSession.lastConfirmedAt <= :confirmedAtOrBefore
              AND parkingSession.startedAt <= :startedAtOrBefore
            ORDER BY parkingSession.lastConfirmedAt ASC, parkingSession.startedAt ASC, parkingSession.id ASC
            """)
    Slice<ParkingSession> findStaleActiveCandidates(
            @Param("confirmedAtOrBefore") Instant confirmedAtOrBefore,
            @Param("startedAtOrBefore") Instant startedAtOrBefore,
            Pageable pageable);

    /**
     * Reminder candidates with no start ceiling.
     *
     * <p>This is deliberately a separate query from
     * {@link #findReminderCandidatesStartedAtOrBefore} rather than one query carrying a
     * {@code (:param IS NULL OR ...)} guard. A bind parameter used as the operand of
     * {@code IS NULL} has no type context, so PostgreSQL cannot infer its type at parse
     * time and rejects the statement with SQLState 42P18 ("could not determine data type
     * of parameter"). H2 infers a type there, which is why the previous single-query form
     * passed the H2-backed tests and still failed on every scheduler tick in production.
     * Keeping the two predicates in two queries means every parameter always appears in a
     * typed comparison, on every database, with no vendor-specific casts.
     */
    @Query("""
            SELECT parkingSession
            FROM ParkingSession parkingSession
            WHERE parkingSession.status = com.parkio.parking.domain.ParkingSessionStatus.ACTIVE
              AND parkingSession.reminderStage = :currentReminderStage
              AND parkingSession.lastConfirmedAt <= :confirmedAtOrBefore
            ORDER BY parkingSession.lastConfirmedAt ASC, parkingSession.startedAt ASC, parkingSession.id ASC
            """)
    Slice<ParkingSession> findReminderCandidates(
            @Param("currentReminderStage") int currentReminderStage,
            @Param("confirmedAtOrBefore") Instant confirmedAtOrBefore,
            Pageable pageable);

    /** Reminder candidates additionally bounded by a start ceiling. See {@link #findReminderCandidates}. */
    @Query("""
            SELECT parkingSession
            FROM ParkingSession parkingSession
            WHERE parkingSession.status = com.parkio.parking.domain.ParkingSessionStatus.ACTIVE
              AND parkingSession.reminderStage = :currentReminderStage
              AND parkingSession.lastConfirmedAt <= :confirmedAtOrBefore
              AND parkingSession.startedAt <= :startedAtOrBefore
            ORDER BY parkingSession.lastConfirmedAt ASC, parkingSession.startedAt ASC, parkingSession.id ASC
            """)
    Slice<ParkingSession> findReminderCandidatesStartedAtOrBefore(
            @Param("currentReminderStage") int currentReminderStage,
            @Param("confirmedAtOrBefore") Instant confirmedAtOrBefore,
            @Param("startedAtOrBefore") Instant startedAtOrBefore,
            Pageable pageable);

    long countByStatus(ParkingSessionStatus status);

    @Query("""
            SELECT parkingSession
            FROM ParkingSession parkingSession
            WHERE parkingSession.status IN (
                  com.parkio.parking.domain.ParkingSessionStatus.COMPLETED,
                  com.parkio.parking.domain.ParkingSessionStatus.CANCELLED
              )
              AND parkingSession.endedAt <= :endedAtOrBefore
            ORDER BY parkingSession.endedAt ASC, parkingSession.id ASC
            """)
    Slice<ParkingSession> findTerminalEndedAtOrBefore(
            @Param("endedAtOrBefore") Instant endedAtOrBefore, Pageable pageable);
}
