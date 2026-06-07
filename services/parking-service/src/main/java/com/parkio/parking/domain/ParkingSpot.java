package com.parkio.parking.domain;

import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate root for a parking spot and its lifecycle. References the contributing
 * user and the photo only by id ({@code ownerUserId}, {@code mediaId}) — no
 * cross-service link, and no media bytes/storage internals (ai-context/03). Pure
 * domain: no framework, JPA, HTTP or PostGIS dependencies.
 *
 * <p>Durations and thresholds below are <em>tunable defaults</em> (ai-context/02);
 * they live here as named constants for this foundation rather than in config.
 */
public final class ParkingSpot {

    public static final int DESCRIPTION_MAX = 1000;
    public static final int ADDRESS_MAX = 512;

    static final Duration ACTIVE_DURATION = Duration.ofMinutes(10);
    static final Duration FIRST_VERIFICATION_DURATION = Duration.ofMinutes(15);
    static final Duration MULTI_VERIFICATION_DURATION = Duration.ofMinutes(20);
    static final int FILLED_REPORTS_TO_FILL = 2;
    static final double INITIAL_CONFIDENCE = 1.0;
    static final double CONFIDENCE_PENALTY = 0.4;
    static final double SUSPICIOUS_CONFIDENCE_THRESHOLD = 0.5;

    private final UUID id;
    private final UUID ownerUserId;
    private final UUID mediaId;
    private final double latitude;
    private final double longitude;
    private final String addressText;
    private final String description;
    private final boolean manualLocationEdited;
    private final Set<VehicleType> suitableVehicleTypes;
    private final ParkingContext parkingContext;
    private final LegalStatus legalStatus;
    private final Set<ViolationReason> violationReasons;
    private ParkingSpotStatus status;
    private double confidenceScore;
    private int verificationCount;
    private int filledReportCount;
    private Instant expiresAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;

    public ParkingSpot(UUID id,
                       UUID ownerUserId,
                       UUID mediaId,
                       double latitude,
                       double longitude,
                       String addressText,
                       String description,
                       boolean manualLocationEdited,
                       Set<VehicleType> suitableVehicleTypes,
                       ParkingContext parkingContext,
                       LegalStatus legalStatus,
                       Set<ViolationReason> violationReasons,
                       ParkingSpotStatus status,
                       double confidenceScore,
                       int verificationCount,
                       int filledReportCount,
                       Instant expiresAt,
                       Instant createdAt,
                       Instant updatedAt,
                       Long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId");
        this.mediaId = Objects.requireNonNull(mediaId, "mediaId");
        this.latitude = validateLatitude(latitude);
        this.longitude = validateLongitude(longitude);
        this.addressText = validateLength(addressText, ADDRESS_MAX, "addressText");
        this.description = validateLength(description, DESCRIPTION_MAX, "description");
        this.manualLocationEdited = manualLocationEdited;
        this.suitableVehicleTypes = requireNonEmptyVehicleTypes(suitableVehicleTypes);
        this.parkingContext = Objects.requireNonNull(parkingContext, "parkingContext");
        this.legalStatus = Objects.requireNonNull(legalStatus, "legalStatus");
        this.violationReasons = copyViolationReasons(violationReasons);
        this.status = Objects.requireNonNull(status, "status");
        this.confidenceScore = confidenceScore;
        this.verificationCount = verificationCount;
        this.filledReportCount = filledReportCount;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /**
     * Creates a new {@link ParkingSpotStatus#ACTIVE} spot valid for the default
     * window. Rejects creation of an illegal/risky spot — no spot is produced.
     */
    public static ParkingSpot create(UUID ownerUserId,
                                     UUID mediaId,
                                     double latitude,
                                     double longitude,
                                     String addressText,
                                     String description,
                                     boolean manualLocationEdited,
                                     Set<VehicleType> suitableVehicleTypes,
                                     ParkingContext parkingContext,
                                     LegalStatus legalStatus,
                                     Set<ViolationReason> violationReasons,
                                     Instant now) {
        if (legalStatus == LegalStatus.ILLEGAL_OR_RISKY) {
            throw new ParkingException(ParkingErrorCode.ILLEGAL_SPOT_REJECTED,
                    "A spot reported as illegal or risky cannot be created.");
        }
        return new ParkingSpot(UUID.randomUUID(), ownerUserId, mediaId, latitude, longitude,
                addressText, description, manualLocationEdited, suitableVehicleTypes, parkingContext,
                legalStatus, violationReasons, ParkingSpotStatus.ACTIVE, INITIAL_CONFIDENCE, 0, 0,
                now.plus(ACTIVE_DURATION), now, now, null);
    }

    /** Applies a user's verification, enforcing ownership and verifiability invariants. */
    public void verify(UUID verifierUserId, VerificationResult result, Instant now) {
        ensureNotOwner(verifierUserId, ParkingErrorCode.OWNER_CANNOT_VERIFY);
        ensureVerifiable(now);
        switch (result) {
            case AVAILABLE -> applyAvailable(now);
            case FILLED -> applyFilledReport();
            case ILLEGAL_OR_RISKY -> status = ParkingSpotStatus.REJECTED;
            case WRONG_VEHICLE_SIZE, INVALID -> applyNegativeSignal();
        }
        this.updatedAt = now;
    }

    /** A non-owner claims an available spot, marking it filled. */
    public void claim(UUID claimerUserId, Instant now) {
        ensureNotOwner(claimerUserId, ParkingErrorCode.OWNER_CANNOT_CLAIM);
        if (status != ParkingSpotStatus.ACTIVE && status != ParkingSpotStatus.VERIFIED) {
            throw new ParkingException(ParkingErrorCode.SPOT_NOT_CLAIMABLE, "Spot is not claimable.");
        }
        if (isTimeExpired(now)) {
            throw new ParkingException(ParkingErrorCode.SPOT_EXPIRED, "Spot has expired.");
        }
        this.status = ParkingSpotStatus.FILLED;
        this.updatedAt = now;
    }

    /** Marks a non-terminal spot expired once its validity window has elapsed. */
    public void markExpired(Instant now) {
        if (isTerminal()) {
            return;
        }
        this.status = ParkingSpotStatus.EXPIRED;
        this.updatedAt = now;
    }

    private void applyAvailable(Instant now) {
        verificationCount++;
        if (status == ParkingSpotStatus.ACTIVE
                || status == ParkingSpotStatus.VERIFIED
                || status == ParkingSpotStatus.SUSPICIOUS) {
            status = ParkingSpotStatus.VERIFIED;
        }
        Duration extension = verificationCount >= 2 ? MULTI_VERIFICATION_DURATION : FIRST_VERIFICATION_DURATION;
        Instant candidate = now.plus(extension);
        if (candidate.isAfter(expiresAt)) {
            expiresAt = candidate;
        }
    }

    private void applyFilledReport() {
        filledReportCount++;
        status = filledReportCount >= FILLED_REPORTS_TO_FILL
                ? ParkingSpotStatus.FILLED
                : ParkingSpotStatus.SUSPICIOUS;
    }

    private void applyNegativeSignal() {
        confidenceScore = Math.max(0.0, confidenceScore - CONFIDENCE_PENALTY);
        if (confidenceScore < SUSPICIOUS_CONFIDENCE_THRESHOLD
                && status != ParkingSpotStatus.FILLED
                && status != ParkingSpotStatus.REJECTED) {
            status = ParkingSpotStatus.SUSPICIOUS;
        }
    }

    private void ensureVerifiable(Instant now) {
        if (isTerminal()) {
            throw new ParkingException(ParkingErrorCode.SPOT_NOT_VERIFIABLE, "Spot can no longer be verified.");
        }
        if (isTimeExpired(now)) {
            throw new ParkingException(ParkingErrorCode.SPOT_EXPIRED, "Spot has expired.");
        }
    }

    private void ensureNotOwner(UUID userId, ParkingErrorCode code) {
        if (isOwnedBy(userId)) {
            throw new ParkingException(code, "The owner cannot perform this action on their own spot.");
        }
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerUserId.equals(userId);
    }

    public boolean isTerminal() {
        return status == ParkingSpotStatus.FILLED
                || status == ParkingSpotStatus.EXPIRED
                || status == ParkingSpotStatus.REJECTED;
    }

    public boolean isTimeExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Whether this spot should appear in nearby search at {@code now}. */
    public boolean isVisibleForSearch(Instant now) {
        return (status == ParkingSpotStatus.ACTIVE || status == ParkingSpotStatus.VERIFIED)
                && !isTimeExpired(now)
                && legalStatus != LegalStatus.ILLEGAL_OR_RISKY;
    }

    private static double validateLatitude(double latitude) {
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        return latitude;
    }

    private static double validateLongitude(double longitude) {
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
        return longitude;
    }

    private static String validateLength(String value, int max, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    private static Set<VehicleType> requireNonEmptyVehicleTypes(Set<VehicleType> types) {
        if (types == null || types.isEmpty()) {
            throw new IllegalArgumentException("at least one suitable vehicle type is required");
        }
        return Set.copyOf(new LinkedHashSet<>(types));
    }

    private static Set<ViolationReason> copyViolationReasons(Set<ViolationReason> reasons) {
        return reasons == null || reasons.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(reasons));
    }

    public UUID id() {
        return id;
    }

    public UUID ownerUserId() {
        return ownerUserId;
    }

    public UUID mediaId() {
        return mediaId;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public String addressText() {
        return addressText;
    }

    public String description() {
        return description;
    }

    public boolean manualLocationEdited() {
        return manualLocationEdited;
    }

    public Set<VehicleType> suitableVehicleTypes() {
        return suitableVehicleTypes;
    }

    public ParkingContext parkingContext() {
        return parkingContext;
    }

    public LegalStatus legalStatus() {
        return legalStatus;
    }

    public Set<ViolationReason> violationReasons() {
        return violationReasons;
    }

    public ParkingSpotStatus status() {
        return status;
    }

    public double confidenceScore() {
        return confidenceScore;
    }

    public int verificationCount() {
        return verificationCount;
    }

    public int filledReportCount() {
        return filledReportCount;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long version() {
        return version;
    }
}
