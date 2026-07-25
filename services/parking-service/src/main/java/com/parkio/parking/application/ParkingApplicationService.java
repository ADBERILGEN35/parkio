package com.parkio.parking.application;

import com.parkio.parking.application.command.CreateSpotCommand;
import com.parkio.parking.application.command.SearchNearbyQuery;
import com.parkio.parking.application.port.MediaAccessPort;
import com.parkio.parking.application.port.MediaReadinessPort;
import com.parkio.parking.application.port.ModerationMetricsPort;
import com.parkio.parking.application.port.OutboxEventAppender;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.application.port.ParkingSpotSearchLogRepository;
import com.parkio.parking.application.port.ParkingSpotStatusHistoryRepository;
import com.parkio.parking.application.port.ParkingSpotVerificationRepository;
import com.parkio.parking.application.port.ParkingSpotViewLogRepository;
import com.parkio.parking.application.result.SpotMediaAccess;
import com.parkio.parking.domain.ModerationPolicy;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotSearchLog;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.domain.ParkingSpotVerification;
import com.parkio.parking.domain.ParkingSpotViewLog;
import com.parkio.parking.domain.ParkingSource;
import com.parkio.parking.domain.VerificationResult;
import com.parkio.parking.domain.event.ParkingSpotActivatedEvent;
import com.parkio.parking.domain.event.ParkingSpotClaimedEvent;
import com.parkio.parking.domain.event.ParkingSpotCreatedEvent;
import com.parkio.parking.domain.event.ParkingSpotExpiredEvent;
import com.parkio.parking.domain.event.ParkingSpotMarkedFilledEvent;
import com.parkio.parking.domain.event.ParkingSpotModerationRetryRequestedEvent;
import com.parkio.parking.domain.event.ParkingSpotReviewFailedEvent;
import com.parkio.parking.domain.event.ParkingSpotVerifiedEvent;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parking-spot use cases: creation, verification, claiming, lazy expiration, owner
 * lookups and nearby search. Depends only on domain types and ports; persistence,
 * PostGIS and Kafka concerns sit behind the ports in infrastructure (ai-context/01).
 *
 * <p>This service owns the spot lifecycle only — never media storage, user
 * profiles, gamification or notifications (ai-context/03). It references media by
 * {@code mediaId}; the single cross-service touchpoint is {@link MediaAccessPort},
 * used to mediate signed photo URLs for spots the requester is allowed to see.
 */
@Service
@Transactional
public class ParkingApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ParkingApplicationService.class);

    private final ParkingSpotRepository spots;
    private final ParkingSpotVerificationRepository verifications;
    private final ParkingSpotStatusHistoryRepository statusHistory;
    private final ParkingSpotViewLogRepository viewLogs;
    private final ParkingSpotSearchLogRepository searchLogs;
    private final OutboxEventAppender outbox;
    private final MediaAccessPort mediaAccess;
    private final MediaReadinessPort mediaReadiness;
    private final ParkingSearchSettings searchSettings;
    private final ParkingSessionService parkingSessions;
    private final ModerationPolicy moderationPolicy;
    private final ModerationMetricsPort moderationMetrics;
    private final Clock clock;

    public ParkingApplicationService(ParkingSpotRepository spots,
                                     ParkingSpotVerificationRepository verifications,
                                     ParkingSpotStatusHistoryRepository statusHistory,
                                     ParkingSpotViewLogRepository viewLogs,
                                     ParkingSpotSearchLogRepository searchLogs,
                                     OutboxEventAppender outbox,
                                     MediaAccessPort mediaAccess,
                                     MediaReadinessPort mediaReadiness,
                                     ParkingSearchSettings searchSettings,
                                     ParkingSessionService parkingSessions,
                                     ModerationPolicy moderationPolicy,
                                     ModerationMetricsPort moderationMetrics,
                                     Clock clock) {
        this.spots = spots;
        this.verifications = verifications;
        this.statusHistory = statusHistory;
        this.viewLogs = viewLogs;
        this.searchLogs = searchLogs;
        this.outbox = outbox;
        this.mediaAccess = mediaAccess;
        this.mediaReadiness = mediaReadiness;
        this.searchSettings = searchSettings;
        this.parkingSessions = parkingSessions;
        this.moderationPolicy = moderationPolicy;
        this.moderationMetrics = moderationMetrics;
        this.clock = clock;
    }

    /**
     * Creates a spot. The referenced media must already be {@code READY} in
     * media-service (uploaded, scanned clean) — this is checked first and fails closed
     * (no spot is persisted if the media is not ready or media-service is unreachable).
     * Illegal/risky submissions are also rejected without persisting.
     */
    public ParkingSpot createSpot(CreateSpotCommand command) {
        // Reject before any write: don't let a spot reference unscanned/unsafe media.
        mediaReadiness.ensureMediaReady(command.mediaId(), command.ownerUserId());
        Instant now = clock.instant();
        ParkingSpot spot = ParkingSpot.create(
                command.ownerUserId(), command.mediaId(), command.latitude(), command.longitude(),
                command.addressText(), command.description(), command.manualLocationEdited(),
                command.suitableVehicleTypes(), command.parkingContext(), command.legalStatus(),
                command.violationReasons(), now, moderationPolicy);
        ParkingSpot saved = spots.save(spot);
        recordHistory(saved, null, "CREATED", now);
        outbox.append(ParkingSpotCreatedEvent.of(saved, now));
        log.info("Spot lifecycle transition spotId={} from=null to={} reason=CREATED "
                        + "moderationDeadlineAt={} attempt=0",
                saved.id(), saved.status(), saved.moderationDeadlineAt());
        return saved;
    }

    /** Opens a spot detail view, hiding non-public spots from unrelated users. */
    public ParkingSpot getSpotForViewer(UUID spotId, UUID viewerUserId, boolean canModerate) {
        ParkingSpot spot = requireSpot(spotId);
        Instant now = clock.instant();
        expireIfElapsed(spot, now);
        if (!spot.isOwnedBy(viewerUserId) && !canModerate && !spot.isVisibleForSearch(now)) {
            throw new ParkingException(ParkingErrorCode.SPOT_NOT_FOUND);
        }
        viewLogs.save(ParkingSpotViewLog.record(spotId, viewerUserId, clock.instant()));
        return spot;
    }

    /**
     * Issues a short-lived signed URL for the photo of a spot the requester may
     * see. The owner can always access their own spot's photo; everyone else only
     * while the spot is publicly visible (ACTIVE/VERIFIED, not expired, not
     * illegal/risky — the same rule as nearby search). Hidden, rejected, filled or
     * expired spots answer {@code SPOT_NOT_FOUND} (404) so spot ids cannot be
     * probed/enumerated.
     *
     * <p>Read-only: visibility is evaluated against the clock without persisting a
     * lazy expiry transition, keeping the transaction free of writes while the
     * media-service call is in flight.
     */
    @Transactional(readOnly = true)
    public SpotMediaAccess getSpotMediaAccessUrl(UUID spotId, UUID requesterUserId) {
        ParkingSpot spot = requireSpot(spotId);
        Instant now = clock.instant();
        if (!spot.isOwnedBy(requesterUserId) && !spot.isVisibleForSearch(now)) {
            throw new ParkingException(ParkingErrorCode.SPOT_NOT_FOUND);
        }
        MediaAccessPort.MediaAccessGrant grant = mediaAccess.requestAccessUrl(spot.mediaId(), requesterUserId);
        return new SpotMediaAccess(spot.id(), grant.mediaId(), grant.accessUrl(), grant.expiresAt());
    }

    @Transactional(readOnly = true)
    public List<ParkingSpot> listMySpots(UUID ownerUserId) {
        return spots.findByOwnerUserId(ownerUserId);
    }

    public ParkingSpot getMySpot(UUID ownerUserId, UUID spotId) {
        ParkingSpot spot = requireSpot(spotId);
        if (!spot.isOwnedBy(ownerUserId)) {
            // Don't reveal the existence of another user's spot here.
            throw new ParkingException(ParkingErrorCode.SPOT_NOT_FOUND);
        }
        expireIfElapsed(spot, clock.instant());
        return spot;
    }

    /** Applies a verification/report from a non-owner who has not verified before. */
    public ParkingSpot verifySpot(UUID spotId, UUID verifierUserId, VerificationResult result) {
        ParkingSpot spot = requireSpot(spotId);
        Instant now = clock.instant();
        if (expireIfElapsed(spot, now)) {
            throw new ParkingException(ParkingErrorCode.SPOT_EXPIRED, "Spot has expired.");
        }
        if (spot.isOwnedBy(verifierUserId)) {
            throw new ParkingException(ParkingErrorCode.OWNER_CANNOT_VERIFY,
                    "The owner cannot verify their own spot.");
        }
        if (verifications.existsBySpotIdAndVerifierUserId(spotId, verifierUserId)) {
            throw new ParkingException(ParkingErrorCode.ALREADY_VERIFIED, "You have already verified this spot.");
        }
        if (result == VerificationResult.AVAILABLE && !spot.isVisibleForSearch(now)) {
            // Align mutations with read visibility: hidden SUSPICIOUS/terminal spots cannot be
            // rehabilitated by users who cannot see them (prevents UUID probing).
            throw new ParkingException(ParkingErrorCode.SPOT_NOT_FOUND);
        }

        ParkingSpotStatus previous = spot.status();
        spot.verify(verifierUserId, result, now);
        verifications.save(ParkingSpotVerification.record(spotId, verifierUserId, result, now));
        ParkingSpot saved = spots.save(spot);

        if (saved.status() != previous) {
            recordHistory(saved, previous, "VERIFICATION_" + result.name(), now);
        }
        emitVerificationEvent(saved, verifierUserId, result, previous, now);
        return saved;
    }

    /** A non-owner claims an ACTIVE/VERIFIED spot, marking it filled. */
    public ParkingSpot claimSpot(UUID spotId, UUID claimerUserId) {
        ParkingSpot spot = requireSpot(spotId);
        Instant now = clock.instant();
        if (expireIfElapsed(spot, now)) {
            throw new ParkingException(ParkingErrorCode.SPOT_EXPIRED, "Spot has expired.");
        }
        if (spot.isOwnedBy(claimerUserId)) {
            throw new ParkingException(ParkingErrorCode.OWNER_CANNOT_CLAIM,
                    "The owner cannot claim their own spot.");
        }

        ParkingSpotStatus previous = spot.status();
        spot.claim(claimerUserId, now);
        var session = parkingSessions.startSession(
                claimerUserId,
                ParkingSource.COMMUNITY,
                spot.latitude(),
                spot.longitude(),
                null,
                null);
        log.info("Community claim prepared spotId={} sessionId={} parkingSource={}",
                spot.id(), session.getId(), session.getParkingSource());
        ParkingSpot saved = spots.save(spot);
        recordHistory(saved, previous, "CLAIMED", now);
        outbox.append(ParkingSpotClaimedEvent.of(saved, claimerUserId, now));
        return saved;
    }

    /**
     * Applies an authoritative moderation rejection without emitting a community
     * rejection event, preventing a parking-to-moderation event loop.
     *
     * @param occurredAt when the moderator decided; older than the spot's current decision
     *                   watermark means an out-of-order delivery and is ignored
     */
    public void rejectSpotByModerator(UUID spotId, UUID moderationRequestId, Instant occurredAt) {
        applyModeratorDecision(spotId, moderationRequestId, occurredAt, false);
    }

    /**
     * Publishes a spot on an explicit moderator approval — the human exit from
     * {@code PENDING_REVIEW}. Without this path a spot the AI was unsure about could never
     * become visible no matter what a moderator decided.
     */
    public void approveSpotByModerator(UUID spotId, UUID moderationRequestId, Instant occurredAt) {
        applyModeratorDecision(spotId, moderationRequestId, occurredAt, true);
    }

    private void applyModeratorDecision(UUID spotId, UUID moderationRequestId, Instant occurredAt, boolean approve) {
        long startedNanos = System.nanoTime();
        ParkingSpot spot = requireSpot(spotId);
        Instant now = clock.instant();
        ParkingSpotStatus previous = spot.status();
        String reason = approve ? "MODERATOR_APPROVED" : "MODERATOR_REJECTED";

        if (spot.isStaleModerationEvent(occurredAt)) {
            log.info("Ignoring stale moderator decision spotId={} moderationRequestId={} transition={} "
                            + "occurredAt={} decidedAt={}",
                    spotId, moderationRequestId, reason, occurredAt, spot.moderationDecidedAt());
            moderationMetrics.recordProcessingDuration(elapsedSince(startedNanos), "STALE");
            return;
        }
        spot.trackModerationRequest(moderationRequestId);

        boolean changed = approve
                ? spot.applyModeratorApproval(now, moderationPolicy)
                : spot.markRejectedByModerator(now);
        if (!changed) {
            // Terminal or already-decided: replays and duplicates settle here as no-ops.
            moderationMetrics.recordProcessingDuration(elapsedSince(startedNanos), "NO_CHANGE");
            return;
        }

        ParkingSpot saved = spots.save(spot);
        if (approve && saved.status() == ParkingSpotStatus.REVIEW_FAILED) {
            reason = ParkingSpotReviewFailedEvent.REASON_STALE_BEFORE_PUBLICATION;
            recordHistory(saved, previous, reason, now);
            outbox.append(ParkingSpotReviewFailedEvent.of(saved, previous, reason, now));
            moderationMetrics.recordModerationFailure(reason);
            recordModerationOutcome(saved, previous, reason, moderationRequestId, now, startedNanos);
            return;
        }
        recordHistory(saved, previous, reason, now);
        if (saved.status() == ParkingSpotStatus.ACTIVE) {
            outbox.append(ParkingSpotActivatedEvent.of(saved, now));
        }
        recordModerationOutcome(saved, previous, reason, moderationRequestId, now, startedNanos);
    }

    /**
     * Applies the AI validation publication gate. Spots stay non-discoverable until
     * {@code PASSED}; uncertain results wait in {@code PENDING_REVIEW}; failures /
     * non-parking risks reject. Unknown statuses and provider gaps are fail-closed
     * (no transition — spot remains {@code PENDING_VALIDATION}).
     *
     * <p>Idempotent on three independent levels: the consumer's inbox drops duplicate
     * {@code eventId}s, {@code occurredAt} is compared against the spot's decision
     * watermark so an out-of-order verdict cannot overwrite a newer state, and the domain
     * transitions themselves only fire from the pending statuses.
     *
     * @param statusName          PASSED / WARNING / FAILED (case-insensitive)
     * @param detectedRiskTypes   advisory risk type names from ai-validation-service
     * @param moderationRequestId the upstream event id, carried for tracing
     * @param occurredAt          when the verdict was produced (ordering watermark)
     */
    public void applyAiValidationResult(UUID parkingSpotId, String statusName, List<String> detectedRiskTypes,
                                        UUID moderationRequestId, Instant occurredAt) {
        long startedNanos = System.nanoTime();
        ParkingSpot spot = requireSpot(parkingSpotId);
        Instant now = clock.instant();
        ParkingSpotStatus previous = spot.status();

        if (spot.isStaleModerationEvent(occurredAt)) {
            log.info("Ignoring stale AI validation result spotId={} moderationRequestId={} "
                            + "occurredAt={} decidedAt={}",
                    parkingSpotId, moderationRequestId, occurredAt, spot.moderationDecidedAt());
            moderationMetrics.recordProcessingDuration(elapsedSince(startedNanos), "STALE");
            return;
        }

        String normalized = statusName == null ? "" : statusName.trim().toUpperCase(Locale.ROOT);
        boolean notParking = detectedRiskTypes != null
                && detectedRiskTypes.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(r -> r.trim().toUpperCase(Locale.ROOT))
                .anyMatch("NOT_A_PARKING_SPOT"::equals);

        spot.trackModerationRequest(moderationRequestId);
        boolean changed;
        String reason;
        if ("PASSED".equals(normalized) && !notParking) {
            changed = spot.applyAiValidationPassed(now, moderationPolicy);
            reason = "AI_PASSED";
        } else if ("WARNING".equals(normalized) && !notParking) {
            changed = spot.applyAiValidationUncertain(now, moderationPolicy);
            reason = "AI_PENDING_REVIEW";
        } else if ("FAILED".equals(normalized) || notParking) {
            changed = spot.applyAiValidationRejected(now);
            reason = "AI_REJECTED";
        } else {
            // Fail-closed: unknown / missing status leaves the spot pending validation.
            moderationMetrics.recordProcessingDuration(elapsedSince(startedNanos), "UNKNOWN_STATUS");
            return;
        }

        if (!changed) {
            moderationMetrics.recordProcessingDuration(elapsedSince(startedNanos), "NO_CHANGE");
            return;
        }
        ParkingSpot saved = spots.save(spot);
        if ("PASSED".equals(normalized) && saved.status() == ParkingSpotStatus.REVIEW_FAILED) {
            reason = ParkingSpotReviewFailedEvent.REASON_STALE_BEFORE_PUBLICATION;
            recordHistory(saved, previous, reason, now);
            outbox.append(ParkingSpotReviewFailedEvent.of(saved, previous, reason, now));
            moderationMetrics.recordModerationFailure(reason);
            recordModerationOutcome(saved, previous, reason, moderationRequestId, now, startedNanos);
            return;
        }
        recordHistory(saved, previous, reason, now);
        if (saved.status() == ParkingSpotStatus.ACTIVE) {
            outbox.append(ParkingSpotActivatedEvent.of(saved, now));
        }
        recordModerationOutcome(saved, previous, reason, moderationRequestId, now, startedNanos);
    }

    /** Expires one locked batch of elapsed, non-terminal spots. */
    public int expireElapsedSpots(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        Instant now = clock.instant();
        int expired = 0;
        for (ParkingSpot spot : spots.findExpiredCandidates(now, batchSize)) {
            if (expireIfElapsed(spot, now)) {
                expired++;
            }
        }
        return expired;
    }

    /**
     * Resolves one locked batch of spots whose moderation deadline elapsed, so no
     * submission can sit pending forever.
     *
     * <p>A spot still at the AI gate gets a bounded retry: the request is re-published
     * through the outbox (never a direct call into ai-validation-service) and the deadline
     * is extended with per-attempt backoff. Once the attempts are exhausted — or as soon as
     * a spot awaiting a <em>human</em> decision breaches its review window, where retrying
     * cannot help — the spot moves to the terminal {@code REVIEW_FAILED} state.
     *
     * @return the number of spots that were retried or failed
     */
    public int processModerationTimeouts(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        Instant now = clock.instant();
        int handled = 0;
        for (ParkingSpot spot : spots.findModerationTimeoutCandidates(now, batchSize)) {
            if (!spot.isModerationOverdue(now)) {
                continue;
            }
            moderationMetrics.recordTimeout(spot.status());
            handled += spot.status() == ParkingSpotStatus.PENDING_VALIDATION
                    ? retryOrFailValidation(spot, now)
                    : failReview(spot, now);
        }
        return handled;
    }

    /** Re-requests the AI gate while attempts remain; otherwise fails the spot terminally. */
    private int retryOrFailValidation(ParkingSpot spot, Instant now) {
        ParkingSpotStatus previous = spot.status();
        if (spot.recordValidationRetry(now, moderationPolicy)) {
            ParkingSpot saved = spots.save(spot);
            outbox.append(ParkingSpotModerationRetryRequestedEvent.of(saved, now));
            moderationMetrics.recordRetry(saved.moderationAttempts());
            log.warn("Moderation retry requested spotId={} moderationRequestId={} transition=RETRY "
                            + "attempt={} queueLatencyMs={} nextDeadlineAt={}",
                    saved.id(), saved.moderationRequestId(), saved.moderationAttempts(),
                    saved.moderationWaitAt(now).toMillis(), saved.moderationDeadlineAt());
            return 1;
        }
        return failModeration(spot, previous, ParkingSpotReviewFailedEvent.REASON_RETRIES_EXHAUSTED, now);
    }

    /** A human review window elapsed; no retry can substitute for the missing decision. */
    private int failReview(ParkingSpot spot, Instant now) {
        return failModeration(spot, spot.status(), ParkingSpotReviewFailedEvent.REASON_REVIEW_TIMEOUT, now);
    }

    private int failModeration(ParkingSpot spot, ParkingSpotStatus previous, String reason, Instant now) {
        if (!spot.markReviewFailed(now)) {
            return 0;
        }
        ParkingSpot saved = spots.save(spot);
        recordHistory(saved, previous, reason, now);
        outbox.append(ParkingSpotReviewFailedEvent.of(saved, previous, reason, now));
        moderationMetrics.recordModerationFailure(reason);
        moderationMetrics.recordQueueLatency(saved.moderationWaitAt(now), saved.status());
        log.error("Moderation failed terminally spotId={} moderationRequestId={} transition={}->{} "
                        + "reason={} attempt={} queueLatencyMs={}",
                saved.id(), saved.moderationRequestId(), previous, saved.status(), reason,
                saved.moderationAttempts(), saved.moderationWaitAt(now).toMillis());
        return 1;
    }

    /** Nearby search filtering out expired/filled/rejected/illegal spots. */
    public List<ParkingSpot> searchNearby(SearchNearbyQuery query) {
        Instant now = clock.instant();
        double radius = resolveRadius(query.radiusMeters());
        int limit = resolveLimit(query.limit());

        List<ParkingSpot> visible = spots.findNearby(query.latitude(), query.longitude(), radius, limit).stream()
                .filter(spot -> spot.isVisibleForSearch(now))
                .limit(limit)
                .toList();

        searchLogs.save(ParkingSpotSearchLog.record(
                query.searcherUserId(), query.latitude(), query.longitude(), radius, visible.size(), now));
        return visible;
    }

    /** Resolves the search radius: default when absent, else bounded to (0, max]. */
    private double resolveRadius(Double requested) {
        if (requested == null) {
            return searchSettings.defaultRadiusMeters();
        }
        if (requested <= 0 || requested > searchSettings.maxRadiusMeters()) {
            throw new IllegalArgumentException(
                    "radius must be between 0 (exclusive) and " + searchSettings.maxRadiusMeters() + " meters");
        }
        return requested;
    }

    /** Resolves the result limit: default when absent, else bounded to (0, max]. */
    private int resolveLimit(Integer requested) {
        if (requested == null) {
            return searchSettings.defaultResultLimit();
        }
        if (requested <= 0 || requested > searchSettings.maxResultLimit()) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + searchSettings.maxResultLimit());
        }
        return requested;
    }

    private ParkingSpot requireSpot(UUID spotId) {
        return spots.findById(spotId)
                .orElseThrow(() -> new ParkingException(ParkingErrorCode.SPOT_NOT_FOUND));
    }

    /**
     * Transitions a time-elapsed, published, non-terminal spot to EXPIRED (with history +
     * event).
     *
     * <p>Spots awaiting moderation are skipped outright: their advertised lifetime has not
     * started, so there is nothing to elapse. This guard matters most on the read paths
     * ({@code getMySpot}, {@code getSpotForViewer}), where an owner merely opening their
     * own pending submission used to expire it — after which the arriving verdict was
     * silently discarded because the spot was no longer pending.
     */
    private boolean expireIfElapsed(ParkingSpot spot, Instant now) {
        if (spot.isPendingModeration() || spot.isTerminal() || !spot.isTimeExpired(now)) {
            return false;
        }
        ParkingSpotStatus previous = spot.status();
        if (!spot.markExpired(now)) {
            return false;
        }
        spots.save(spot);
        recordHistory(spot, previous, "EXPIRED", now);
        outbox.append(ParkingSpotExpiredEvent.of(spot, now));
        if (spot.activatedAt() == null) {
            // Unreachable by construction — a spot cannot leave the pending statuses without
            // being activated, rejected or failed. Alarm rather than fail: this counter is the
            // regression detector for the very defect this lifecycle was built to prevent.
            moderationMetrics.recordExpiredBeforeApproved();
            log.error("Invariant violated: spot expired before it was ever published spotId={} "
                    + "previousStatus={} createdAt={}", spot.id(), previous, spot.createdAt());
        }
        return true;
    }

    /** Emits the queue-latency metric and the structured lifecycle log for one verdict. */
    private void recordModerationOutcome(ParkingSpot spot, ParkingSpotStatus previous, String reason,
                                         UUID moderationRequestId, Instant now, long startedNanos) {
        Duration queueLatency = spot.moderationWaitAt(now);
        Duration processing = elapsedSince(startedNanos);
        moderationMetrics.recordQueueLatency(queueLatency, spot.status());
        moderationMetrics.recordProcessingDuration(processing, reason);
        log.info("Spot lifecycle transition spotId={} moderationRequestId={} transition={}->{} reason={} "
                        + "attempt={} queueLatencyMs={} processingMs={} activatedAt={} expiresAt={}",
                spot.id(), moderationRequestId, previous, spot.status(), reason, spot.moderationAttempts(),
                queueLatency.toMillis(), processing.toMillis(), spot.activatedAt(), spot.expiresAt());
    }

    private static Duration elapsedSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    private void emitVerificationEvent(ParkingSpot spot, UUID verifierUserId, VerificationResult result,
                                       ParkingSpotStatus previous, Instant now) {
        if (spot.status() == ParkingSpotStatus.FILLED && previous != ParkingSpotStatus.FILLED) {
            outbox.append(ParkingSpotMarkedFilledEvent.of(spot, now));
        } else if (result == VerificationResult.AVAILABLE
                || result == VerificationResult.ILLEGAL_OR_RISKY) {
            outbox.append(ParkingSpotVerifiedEvent.of(spot, verifierUserId, result, now));
        }
        // Single filled-report (→SUSPICIOUS) and wrong-vehicle/invalid signals carry
        // no dedicated event; the status-history row captures them.
    }

    private void recordHistory(ParkingSpot spot, ParkingSpotStatus previous, String reason, Instant now) {
        statusHistory.save(ParkingSpotStatusHistory.record(spot.id(), previous, spot.status(), reason, now));
    }
}
