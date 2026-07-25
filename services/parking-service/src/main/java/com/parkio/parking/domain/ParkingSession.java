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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
 * available only through {@link #complete(Instant, ParkingSessionCompletionType)},
 * {@link #cancel(Instant)}, and {@link #confirmActive(Instant)}.
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

    @Column(name = "last_confirmed_at")
    private Instant lastConfirmedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_type")
    private ParkingSessionCompletionType completionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_reason")
    private ParkingSessionCompletionReason completionReason;

    /** Progressive reminder stage: 0=NONE, 1=FIRST, 2=SECOND. */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "reminder_stage", nullable = false)
    private int reminderStage = 0;

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
                           Instant lastConfirmedAt,
                           ParkingSessionCompletionType completionType,
                           ParkingSessionCompletionReason completionReason,
                           int reminderStage,
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
        this.lastConfirmedAt = lastConfirmedAt;
        this.completionType = completionType;
        this.completionReason = completionReason;
        this.reminderStage = reminderStage;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
        validateLifecycle(status, startedAt, endedAt, completionType, completionReason);
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
                null,
                null,
                ParkingSessionReminderStage.NONE.wireValue(),
                startedAt,
                startedAt,
                null);
    }

    /**
     * Completes an ACTIVE session with an explicit completion provenance.
     * Completed sessions are terminal. Public {@code completionType} is derived from
     * {@code reason} so API clients keep seeing MANUAL/AUTO.
     */
    public void complete(Instant now, ParkingSessionCompletionReason reason) {
        ParkingSessionCompletionReason completion =
                Objects.requireNonNull(reason, "completionReason");
        end(ParkingSessionStatus.COMPLETED, now, completion);
    }

    /**
     * @deprecated Prefer {@link #complete(Instant, ParkingSessionCompletionReason)}.
     * Kept for call-site compatibility during the reason migration.
     */
    @Deprecated
    public void complete(Instant now, ParkingSessionCompletionType type) {
        complete(now, ParkingSessionCompletionReason.fromLegacyType(type));
    }

    /** Cancels an ACTIVE session. Cancelled sessions are always MANUAL. */
    public void cancel(Instant now) {
        end(ParkingSessionStatus.CANCELLED, now, ParkingSessionCompletionReason.MANUAL);
    }

    /**
     * Extends the confirmation window for an ACTIVE session ("Yes, still parked").
     * Sets {@code lastConfirmedAt} to {@code now} and resets reminder stages so the
     * 24h/48h windows restart from this confirmation.
     */
    public void confirmActive(Instant now) {
        if (!isActive()) {
            throw new ParkingException(
                    ParkingErrorCode.PARKING_SESSION_NOT_ACTIVE,
                    "Only an active parking session can be confirmed.");
        }
        Instant confirmedAt = Objects.requireNonNull(now, "now");
        this.lastConfirmedAt = confirmedAt;
        this.reminderStage = ParkingSessionReminderStage.NONE.wireValue();
        this.updatedAt = confirmedAt;
    }

    /**
     * Records that a reminder stage was published. Idempotent for the same or lower stage.
     */
    public void markReminderSent(ParkingSessionReminderStage stage, Instant now) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(now, "now");
        if (!isActive()) {
            throw new ParkingException(
                    ParkingErrorCode.PARKING_SESSION_NOT_ACTIVE,
                    "Only an active parking session can receive reminders.");
        }
        if (stage == ParkingSessionReminderStage.NONE) {
            throw new IllegalArgumentException("Cannot mark NONE as sent");
        }
        if (getReminderStage().hasReached(stage)) {
            return;
        }
        this.reminderStage = stage.wireValue();
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == ParkingSessionStatus.ACTIVE;
    }

    private void end(
            ParkingSessionStatus terminalStatus,
            Instant now,
            ParkingSessionCompletionReason reason) {
        if (!isActive()) {
            throw new ParkingException(
                    ParkingErrorCode.PARKING_SESSION_NOT_ACTIVE,
                    "Only an active parking session can be completed or cancelled.");
        }
        Instant terminalTime = Objects.requireNonNull(now, "now");
        if (terminalTime.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt cannot be before startedAt");
        }
        ParkingSessionCompletionReason completion =
                Objects.requireNonNull(reason, "completionReason");
        status = terminalStatus;
        endedAt = terminalTime;
        completionReason = completion;
        completionType = completion.toCompletionType();
        updatedAt = terminalTime;
    }

    private static void validateLifecycle(
            ParkingSessionStatus status,
            Instant startedAt,
            Instant endedAt,
            ParkingSessionCompletionType completionType,
            ParkingSessionCompletionReason completionReason) {
        if (status == ParkingSessionStatus.ACTIVE && endedAt != null) {
            throw new IllegalArgumentException("endedAt must be null while a parking session is active");
        }
        if (status != ParkingSessionStatus.ACTIVE && endedAt == null) {
            throw new IllegalArgumentException("endedAt is required for a terminal parking session");
        }
        if (endedAt != null && endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt cannot be before startedAt");
        }
        if (status == ParkingSessionStatus.ACTIVE
                && (completionType != null || completionReason != null)) {
            throw new IllegalArgumentException(
                    "completionType/completionReason must be null while a parking session is active");
        }
        if (status == ParkingSessionStatus.COMPLETED) {
            if (completionType != ParkingSessionCompletionType.MANUAL
                    && completionType != ParkingSessionCompletionType.AUTO) {
                throw new IllegalArgumentException("COMPLETED sessions require MANUAL or AUTO completionType");
            }
            if (completionReason == null) {
                throw new IllegalArgumentException("COMPLETED sessions require completionReason");
            }
        }
        if (status == ParkingSessionStatus.CANCELLED) {
            if (completionType != ParkingSessionCompletionType.MANUAL) {
                throw new IllegalArgumentException("CANCELLED sessions require MANUAL completionType");
            }
            if (completionReason == null
                    || completionReason == ParkingSessionCompletionReason.AUTO_TIMEOUT) {
                throw new IllegalArgumentException("CANCELLED sessions require a non-AUTO completionReason");
            }
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

    public Instant getLastConfirmedAt() {
        return lastConfirmedAt;
    }

    public ParkingSessionCompletionType getCompletionType() {
        return completionType;
    }

    public ParkingSessionCompletionReason getCompletionReason() {
        return completionReason;
    }

    public ParkingSessionReminderStage getReminderStage() {
        return ParkingSessionReminderStage.fromWire(reminderStage);
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
