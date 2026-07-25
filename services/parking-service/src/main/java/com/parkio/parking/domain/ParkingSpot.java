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
 * <p><strong>Lifetime rule.</strong> The advertised user-visible lifetime is never
 * consumed by moderation. While pending, {@code expiresAt} is {@code null} — there is
 * no placeholder far-future value that could leak into countdowns or client math. The
 * real deadline is computed <em>exactly once</em>, at publication, by
 * {@link #startLifetime}. {@link #activatedAt()} is the idempotence key: once set it is
 * never recomputed, so duplicate approvals cannot extend a spot's life.
 *
 * <p><strong>Freshness ceiling.</strong> Even a correct late approval must not publish
 * an availability report that is already too old to trust. {@link ModerationPolicy#isStillPublishable}
 * is checked on every publication path; past that age the spot becomes
 * {@link ParkingSpotStatus#REVIEW_FAILED} instead of {@code ACTIVE}.
 *
 * <p>Durations and thresholds are supplied per-call through {@link ModerationPolicy}
 * (bound from {@code parkio.parking.moderation.*}); the verification extensions below
 * remain named constants.
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
    private Instant activatedAt;
    private Instant moderationDeadlineAt;
    private int moderationAttempts;
    private Instant moderationDecidedAt;
    private UUID moderationRequestId;

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
                       Long version,
                       Instant activatedAt,
                       Instant moderationDeadlineAt,
                       int moderationAttempts,
                       Instant moderationDecidedAt,
                       UUID moderationRequestId) {
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
        this.expiresAt = expiresAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
        this.activatedAt = activatedAt;
        // Rows written before the lifecycle migration are treated as already past their
        // moderation deadline; the timeout job resolves them instead of leaving them pending.
        this.moderationDeadlineAt = moderationDeadlineAt != null ? moderationDeadlineAt : createdAt;
        this.moderationAttempts = Math.max(0, moderationAttempts);
        this.moderationDecidedAt = moderationDecidedAt;
        this.moderationRequestId = moderationRequestId;
    }

    /**
     * Creates a new {@link ParkingSpotStatus#PENDING_VALIDATION} spot. Spots are not
     * publicly discoverable, and their advertised lifetime has not started, until the
     * moderation pipeline publishes them. {@code expiresAt} stays {@code null} while
     * pending — no pending spot is ever time-expired (see {@link #markExpired}). Rejects
     * creation of an illegal/risky spot — no spot is produced.
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
                                     Instant now,
                                     ModerationPolicy policy) {
        if (legalStatus == LegalStatus.ILLEGAL_OR_RISKY) {
            throw new ParkingException(ParkingErrorCode.ILLEGAL_SPOT_REJECTED,
                    "A spot reported as illegal or risky cannot be created.");
        }
        return new ParkingSpot(UUID.randomUUID(), ownerUserId, mediaId, latitude, longitude,
                addressText, description, manualLocationEdited, suitableVehicleTypes, parkingContext,
                legalStatus, violationReasons, ParkingSpotStatus.PENDING_VALIDATION, INITIAL_CONFIDENCE, 0, 0,
                null, now, now, null,
                null, now.plus(policy.validationTimeout()), 0, null, null);
    }

    /**
     * Promotes a spot past the AI publication gate to {@link ParkingSpotStatus#ACTIVE}
     * and starts its advertised lifetime — or to {@link ParkingSpotStatus#REVIEW_FAILED}
     * when the underlying report is past {@link ModerationPolicy#maxPublishableAge()}.
     * Only the pending statuses may transition; a replayed pass is a no-op.
     *
     * @return {@code true} if the status changed
     */
    public boolean applyAiValidationPassed(Instant now, ModerationPolicy policy) {
        return publishFromPending(now, policy);
    }

    /**
     * Publishes a spot on an explicit moderator approval, the human counterpart of
     * {@link #applyAiValidationPassed}. Shares the same publication / freshness logic so
     * TTL calculation cannot drift between the AI and human exits.
     *
     * @return {@code true} if the status changed
     */
    public boolean applyModeratorApproval(Instant now, ModerationPolicy policy) {
        return publishFromPending(now, policy);
    }

    /**
     * Shared publication path for AI and human approval. Enforces the max-publishable-age
     * ceiling and starts the advertised lifetime exactly once.
     */
    private boolean publishFromPending(Instant now, ModerationPolicy policy) {
        if (!status.isPendingModeration()) {
            return false;
        }
        if (!policy.isStillPublishable(createdAt, now)) {
            return markReviewFailed(now);
        }
        this.status = ParkingSpotStatus.ACTIVE;
        startLifetime(now, policy);
        this.moderationDecidedAt = now;
        this.updatedAt = now;
        return true;
    }

    /**
     * Holds a spot for human/moderation attention when AI is uncertain, and restarts the
     * deadline clock against the (longer) human review window. Only
     * {@code PENDING_VALIDATION} may transition (already-{@code PENDING_REVIEW} is a no-op).
     *
     * @return {@code true} if the status changed
     */
    public boolean applyAiValidationUncertain(Instant now, ModerationPolicy policy) {
        if (status != ParkingSpotStatus.PENDING_VALIDATION) {
            return false;
        }
        this.status = ParkingSpotStatus.PENDING_REVIEW;
        this.moderationDeadlineAt = now.plus(policy.reviewTimeout());
        this.moderationDecidedAt = now;
        this.updatedAt = now;
        return true;
    }

    /**
     * Rejects a spot when AI determines it is not valid parking (e.g. keyboard photo).
     * Applies from AI-gated pending statuses only.
     *
     * @return {@code true} if the status changed
     */
    public boolean applyAiValidationRejected(Instant now) {
        if (!status.isPendingModeration()) {
            return false;
        }
        this.status = ParkingSpotStatus.REJECTED;
        this.moderationDecidedAt = now;
        this.updatedAt = now;
        return true;
    }

    /**
     * Records a bounded re-request of the AI publication gate for a spot whose validation
     * deadline elapsed, extending the deadline with per-attempt backoff.
     *
     * @return {@code true} when another attempt is available and was recorded
     */
    public boolean recordValidationRetry(Instant now, ModerationPolicy policy) {
        if (status != ParkingSpotStatus.PENDING_VALIDATION || moderationAttempts >= policy.maxValidationAttempts()) {
            return false;
        }
        this.moderationAttempts++;
        this.moderationDeadlineAt = now.plus(policy.validationDeadlineFor(moderationAttempts));
        this.updatedAt = now;
        return true;
    }

    /**
     * Moves a spot the pipeline never resolved into the terminal
     * {@link ParkingSpotStatus#REVIEW_FAILED} state — the explicit alternative to leaving
     * it pending forever. Terminal, so it can never later become visible.
     *
     * @return {@code true} if the status changed
     */
    public boolean markReviewFailed(Instant now) {
        if (!status.isPendingModeration()) {
            return false;
        }
        this.status = ParkingSpotStatus.REVIEW_FAILED;
        this.moderationDecidedAt = now;
        this.updatedAt = now;
        return true;
    }

    /** Whether the moderation pipeline has not resolved this spot by {@code now}. */
    public boolean isModerationOverdue(Instant now) {
        return status.isPendingModeration() && !now.isBefore(moderationDeadlineAt);
    }

    /**
     * Whether an inbound moderation event is <em>strictly older</em> than the decision
     * already applied. Duplicate delivery is caught upstream by the inbox (by event id);
     * this guards the harder case of out-of-order delivery, where a stale verdict must not
     * overwrite a newer lifecycle state.
     *
     * <p>Deliberately strict-only: two decisions stamped at the same instant are
     * indistinguishable by time, so the comparison lets them through and defers to the
     * status guards rather than silently dropping a legitimate second decision. Events with
     * no timestamp are likewise treated as current — the ordering guard fails open, while
     * the status guards never do.
     */
    public boolean isStaleModerationEvent(Instant occurredAt) {
        return occurredAt != null && moderationDecidedAt != null && occurredAt.isBefore(moderationDecidedAt);
    }

    /** Associates the in-flight moderation request (the upstream event id) for tracing. */
    public void trackModerationRequest(UUID requestId) {
        if (requestId != null) {
            this.moderationRequestId = requestId;
        }
    }

    /**
     * Computes the advertised expiry exactly once, at publication. Re-entry is a no-op, so
     * duplicate approvals cannot repeatedly extend a spot's life. Callers must already have
     * confirmed {@link ModerationPolicy#isStillPublishable}; the full {@code activeDuration}
     * is then granted from <em>this</em> instant for approvals that are still fresh.
     */
    private void startLifetime(Instant now, ModerationPolicy policy) {
        if (activatedAt != null) {
            return;
        }
        this.activatedAt = now;
        this.expiresAt = now.plus(policy.activeDuration());
    }

    /** Applies a user's verification, enforcing ownership and verifiability invariants. */
    public void verify(UUID verifierUserId, VerificationResult result, Instant now) {
        ensureNotOwner(verifierUserId, ParkingErrorCode.OWNER_CANNOT_VERIFY);
        ensureVerifiable(now);
        switch (result) {
            case AVAILABLE -> applyAvailable(now);
            case FILLED -> applyFilledReport();
            case ILLEGAL_OR_RISKY -> applyIllegalOrRiskySignal();
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

    /**
     * Marks a published, non-terminal spot expired once its validity window has elapsed.
     *
     * <p>A spot still waiting on moderation is never expired here: its advertised lifetime
     * has not started, so there is nothing to elapse. This is the guard the previous
     * implementation lacked — an owner simply opening their own pending spot used to
     * expire it, after which the arriving approval was silently discarded.
     *
     * @return {@code true} if the status changed
     */
    public boolean markExpired(Instant now) {
        if (isTerminal() || status.isPendingModeration()) {
            return false;
        }
        this.status = ParkingSpotStatus.EXPIRED;
        this.updatedAt = now;
        return true;
    }

    /** Applies an authoritative moderator rejection without emitting another report. */
    public boolean markRejectedByModerator(Instant now) {
        if (isTerminal()) {
            return false;
        }
        this.status = ParkingSpotStatus.REJECTED;
        this.updatedAt = now;
        return true;
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
        if (expiresAt == null || candidate.isAfter(expiresAt)) {
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

    private void applyIllegalOrRiskySignal() {
        confidenceScore = Math.max(0.0, confidenceScore - CONFIDENCE_PENALTY);
        status = ParkingSpotStatus.SUSPICIOUS;
    }

    private void ensureVerifiable(Instant now) {
        if (status != ParkingSpotStatus.ACTIVE && status != ParkingSpotStatus.VERIFIED
                && status != ParkingSpotStatus.SUSPICIOUS) {
            // Pending AI gate / terminal / other: not community-verifiable until published.
            throw new ParkingException(ParkingErrorCode.SPOT_NOT_VERIFIABLE, "Spot can no longer be verified.");
        }
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
        return status.isTerminal();
    }

    /** Whether the spot is still waiting on the moderation pipeline (AI or human). */
    public boolean isPendingModeration() {
        return status.isPendingModeration();
    }

    /**
     * Whether the advertised validity window has elapsed. Pending spots (and any row whose
     * {@code expiresAt} is still null) have no running window, so they are never time-expired.
     */
    public boolean isTimeExpired(Instant now) {
        return !status.isPendingModeration() && expiresAt != null && !now.isBefore(expiresAt);
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

    /** When the spot was published and its advertised lifetime began; null while pending. */
    public Instant activatedAt() {
        return activatedAt;
    }

    /** When the moderation pipeline must have resolved this spot by. */
    public Instant moderationDeadlineAt() {
        return moderationDeadlineAt;
    }

    /** Bounded count of AI publication-gate re-requests already made. */
    public int moderationAttempts() {
        return moderationAttempts;
    }

    /** When the last moderation verdict was applied; the out-of-order guard's watermark. */
    public Instant moderationDecidedAt() {
        return moderationDecidedAt;
    }

    /** Correlation id of the moderation request in flight (the upstream event id). */
    public UUID moderationRequestId() {
        return moderationRequestId;
    }

    /** How long this spot has waited on moderation, for queue-latency observability. */
    public Duration moderationWaitAt(Instant now) {
        return Duration.between(createdAt, now);
    }
}
