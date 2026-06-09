package com.parkio.parking.application;

import com.parkio.parking.application.command.CreateSpotCommand;
import com.parkio.parking.application.command.SearchNearbyQuery;
import com.parkio.parking.application.port.MediaAccessPort;
import com.parkio.parking.application.port.OutboxEventAppender;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.application.port.ParkingSpotSearchLogRepository;
import com.parkio.parking.application.port.ParkingSpotStatusHistoryRepository;
import com.parkio.parking.application.port.ParkingSpotVerificationRepository;
import com.parkio.parking.application.port.ParkingSpotViewLogRepository;
import com.parkio.parking.application.result.SpotMediaAccess;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotSearchLog;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.domain.ParkingSpotVerification;
import com.parkio.parking.domain.ParkingSpotViewLog;
import com.parkio.parking.domain.VerificationResult;
import com.parkio.parking.domain.event.ParkingSpotClaimedEvent;
import com.parkio.parking.domain.event.ParkingSpotCreatedEvent;
import com.parkio.parking.domain.event.ParkingSpotExpiredEvent;
import com.parkio.parking.domain.event.ParkingSpotMarkedFilledEvent;
import com.parkio.parking.domain.event.ParkingSpotVerifiedEvent;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

    private final ParkingSpotRepository spots;
    private final ParkingSpotVerificationRepository verifications;
    private final ParkingSpotStatusHistoryRepository statusHistory;
    private final ParkingSpotViewLogRepository viewLogs;
    private final ParkingSpotSearchLogRepository searchLogs;
    private final OutboxEventAppender outbox;
    private final MediaAccessPort mediaAccess;
    private final ParkingSearchSettings searchSettings;
    private final Clock clock;

    public ParkingApplicationService(ParkingSpotRepository spots,
                                     ParkingSpotVerificationRepository verifications,
                                     ParkingSpotStatusHistoryRepository statusHistory,
                                     ParkingSpotViewLogRepository viewLogs,
                                     ParkingSpotSearchLogRepository searchLogs,
                                     OutboxEventAppender outbox,
                                     MediaAccessPort mediaAccess,
                                     ParkingSearchSettings searchSettings,
                                     Clock clock) {
        this.spots = spots;
        this.verifications = verifications;
        this.statusHistory = statusHistory;
        this.viewLogs = viewLogs;
        this.searchLogs = searchLogs;
        this.outbox = outbox;
        this.mediaAccess = mediaAccess;
        this.searchSettings = searchSettings;
        this.clock = clock;
    }

    /** Creates a spot. Rejects illegal/risky submissions — no spot is persisted. */
    public ParkingSpot createSpot(CreateSpotCommand command) {
        Instant now = clock.instant();
        ParkingSpot spot = ParkingSpot.create(
                command.ownerUserId(), command.mediaId(), command.latitude(), command.longitude(),
                command.addressText(), command.description(), command.manualLocationEdited(),
                command.suitableVehicleTypes(), command.parkingContext(), command.legalStatus(),
                command.violationReasons(), now);
        ParkingSpot saved = spots.save(spot);
        recordHistory(saved, null, "CREATED", now);
        outbox.append(ParkingSpotCreatedEvent.of(saved, now));
        return saved;
    }

    /** Opens a spot's public detail view, expiring it lazily and logging the view. */
    public ParkingSpot getSpotForViewer(UUID spotId, UUID viewerUserId) {
        ParkingSpot spot = requireSpot(spotId);
        expireIfElapsed(spot, clock.instant());
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
        ParkingSpot saved = spots.save(spot);
        recordHistory(saved, previous, "CLAIMED", now);
        outbox.append(ParkingSpotClaimedEvent.of(saved, claimerUserId, now));
        return saved;
    }

    /**
     * Applies an authoritative moderation rejection without emitting a community
     * rejection event, preventing a parking-to-moderation event loop.
     */
    public void rejectSpotByModerator(UUID spotId) {
        ParkingSpot spot = requireSpot(spotId);
        Instant now = clock.instant();
        ParkingSpotStatus previous = spot.status();
        if (spot.markRejectedByModerator(now)) {
            spots.save(spot);
            recordHistory(spot, previous, "MODERATOR_REJECTED", now);
        }
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

    /** Transitions a time-elapsed, non-terminal spot to EXPIRED (with history + event). */
    private boolean expireIfElapsed(ParkingSpot spot, Instant now) {
        if (spot.isTerminal() || !spot.isTimeExpired(now)) {
            return false;
        }
        ParkingSpotStatus previous = spot.status();
        spot.markExpired(now);
        spots.save(spot);
        recordHistory(spot, previous, "EXPIRED", now);
        outbox.append(ParkingSpotExpiredEvent.of(spot, now));
        return true;
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
