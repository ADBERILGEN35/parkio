package com.parkio.parking.domain;

import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root and JPA entity for one user's parking session.
 *
 * <p>The approved model intentionally uses a single class for domain behavior and
 * persistence. State and location have no public setters; lifecycle changes are
 * available only through {@link #complete(Instant)} and {@link #cancel(Instant)}.
 * The derived PostGIS {@code location} column is maintained by Flyway's database
 * trigger and therefore is not mapped here.
 */
@Entity
@Table(name = "parking_sessions")
public class ParkingSession {

    private static final int ESTIMATED_FEE_PRECISION = 12;
    private static final int ESTIMATED_FEE_SCALE = 2;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ParkingSessionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "parking_source", nullable = false, updatable = false)
    private ParkingSource parkingSource;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "latitude", nullable = false, updatable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false, updatable = false)
    private double longitude;

    @Column(name = "estimated_fee", precision = 12, scale = 2)
    private BigDecimal estimatedFee;

    @Column(name = "reminder_at")
    private Instant reminderAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ParkingSession() {
        // for JPA
    }

    private ParkingSession(UUID id,
                           UUID userId,
                           ParkingSessionStatus status,
                           ParkingSource parkingSource,
                           Instant startedAt,
                           Instant endedAt,
                           double latitude,
                           double longitude,
                           BigDecimal estimatedFee,
                           Instant reminderAt,
                           Instant createdAt,
                           Instant updatedAt,
                           Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.status = Objects.requireNonNull(status, "status");
        this.parkingSource = Objects.requireNonNull(parkingSource, "parkingSource");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.endedAt = endedAt;
        this.latitude = validateLatitude(latitude);
        this.longitude = validateLongitude(longitude);
        this.estimatedFee = validateEstimatedFee(estimatedFee);
        this.reminderAt = reminderAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
        validateLifecycle(status, startedAt, endedAt);
    }

    /** Creates a new ACTIVE session using server-controlled timestamps. */
    public static ParkingSession start(UUID userId,
                                       ParkingSource parkingSource,
                                       double latitude,
                                       double longitude,
                                       BigDecimal estimatedFee,
                                       Instant reminderAt,
                                       Instant now) {
        Instant startedAt = Objects.requireNonNull(now, "now");
        return new ParkingSession(
                UUID.randomUUID(),
                userId,
                ParkingSessionStatus.ACTIVE,
                parkingSource,
                startedAt,
                null,
                latitude,
                longitude,
                estimatedFee,
                reminderAt,
                startedAt,
                startedAt,
                null);
    }

    /** Completes an ACTIVE session. Completed sessions are terminal. */
    public void complete(Instant now) {
        end(ParkingSessionStatus.COMPLETED, now);
    }

    /** Cancels an ACTIVE session. Cancelled sessions are terminal. */
    public void cancel(Instant now) {
        end(ParkingSessionStatus.CANCELLED, now);
    }

    public boolean isActive() {
        return status == ParkingSessionStatus.ACTIVE;
    }

    private void end(ParkingSessionStatus terminalStatus, Instant now) {
        if (!isActive()) {
            throw new ParkingException(
                    ParkingErrorCode.PARKING_SESSION_NOT_ACTIVE,
                    "Only an active parking session can be completed or cancelled.");
        }
        Instant terminalTime = Objects.requireNonNull(now, "now");
        if (terminalTime.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt cannot be before startedAt");
        }
        status = terminalStatus;
        endedAt = terminalTime;
        updatedAt = terminalTime;
    }

    private static void validateLifecycle(
            ParkingSessionStatus status, Instant startedAt, Instant endedAt) {
        if (status == ParkingSessionStatus.ACTIVE && endedAt != null) {
            throw new IllegalArgumentException("endedAt must be null while a parking session is active");
        }
        if (status != ParkingSessionStatus.ACTIVE && endedAt == null) {
            throw new IllegalArgumentException("endedAt is required for a terminal parking session");
        }
        if (endedAt != null && endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt cannot be before startedAt");
        }
    }

    private static double validateLatitude(double latitude) {
        if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        return latitude;
    }

    private static double validateLongitude(double longitude) {
        if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
        return longitude;
    }

    private static BigDecimal validateEstimatedFee(BigDecimal estimatedFee) {
        if (estimatedFee == null) {
            return null;
        }
        if (estimatedFee.signum() < 0) {
            throw new IllegalArgumentException("estimatedFee cannot be negative");
        }

        BigDecimal exactValue;
        try {
            exactValue = estimatedFee.setScale(ESTIMATED_FEE_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "estimatedFee must be representable exactly with at most 2 decimal places",
                    exception);
        }
        if (exactValue.precision() > ESTIMATED_FEE_PRECISION) {
            throw new IllegalArgumentException("estimatedFee exceeds NUMERIC(12,2)");
        }
        return exactValue;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public ParkingSessionStatus getStatus() {
        return status;
    }

    public ParkingSource getParkingSource() {
        return parkingSource;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public BigDecimal getEstimatedFee() {
        return estimatedFee;
    }

    public Instant getReminderAt() {
        return reminderAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
