package com.parkio.user.application;

import com.parkio.user.application.port.RecentParkingRepository;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.RecentParking;
import com.parkio.user.domain.place.RecentParkingTargetKind;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recently used parking (WP-SPA-07). Explicit recording API only — no impression writes.
 */
@Service
@Transactional
public class RecentParkingApplicationService {

    private final RecentParkingRepository recents;
    private final UserProfileRepository profiles;
    private final Clock clock;
    private final int maxPerUser;

    public RecentParkingApplicationService(
            RecentParkingRepository recents,
            UserProfileRepository profiles,
            Clock clock,
            @Value("${parkio.spa.recents.parking-limit:20}") int maxPerUser) {
        this.recents = recents;
        this.profiles = profiles;
        this.clock = clock;
        this.maxPerUser = maxPerUser > 0 ? maxPerUser : RecentParking.DEFAULT_MAX_PER_USER;
    }

    @Transactional(readOnly = true)
    public List<RecentParking> list(UUID authUserId) {
        return recents.findAllByUserProfileIdOrderByLastUsedAtDesc(requireProfile(authUserId).id());
    }

    public RecentParking record(UUID authUserId, RecentParkingTargetKind targetKind, UUID targetId) {
        if (targetId == null) {
            throw new UserException(UserErrorCode.INVALID_RECENT_PARKING_TARGET);
        }
        if (targetKind == null || targetKind != RecentParkingTargetKind.MUNICIPAL_FACILITY) {
            throw new UserException(UserErrorCode.UNSUPPORTED_RECENT_PARKING_TARGET);
        }
        UUID profileId = requireProfile(authUserId).id();
        var existing = recents.findByUserProfileIdAndTarget(profileId, targetKind, targetId);
        if (existing.isPresent()) {
            RecentParking current = existing.get();
            current.recordUse(clock.instant());
            return pruneAfter(recents.save(current), profileId);
        }
        try {
            return pruneAfter(
                    recents.save(RecentParking.create(profileId, targetKind, targetId, clock.instant())),
                    profileId);
        } catch (DataIntegrityViolationException ex) {
            RecentParking raced = recents.findByUserProfileIdAndTarget(profileId, targetKind, targetId)
                    .orElseThrow(() -> new UserException(UserErrorCode.INVALID_RECENT_PARKING_TARGET));
            raced.recordUse(clock.instant());
            return pruneAfter(recents.save(raced), profileId);
        }
    }

    public void delete(UUID authUserId, UUID recentId) {
        UUID profileId = requireProfile(authUserId).id();
        RecentParking existing = recents.findByIdAndUserProfileId(recentId, profileId)
                .orElseThrow(() -> new UserException(UserErrorCode.RECENT_PARKING_NOT_FOUND));
        recents.deleteByIdAndUserProfileId(existing.id(), profileId);
    }

    public void clearAll(UUID authUserId) {
        UUID profileId = requireProfile(authUserId).id();
        recents.deleteAllByUserProfileId(profileId);
    }

    private RecentParking pruneAfter(RecentParking kept, UUID profileId) {
        List<RecentParking> ordered = recents.findAllByUserProfileIdOrderByLastUsedAtDesc(profileId);
        if (ordered.size() <= maxPerUser) {
            return kept;
        }
        for (int i = maxPerUser; i < ordered.size(); i++) {
            RecentParking excess = ordered.get(i);
            recents.deleteByIdAndUserProfileId(excess.id(), profileId);
        }
        return kept;
    }

    private UserProfile requireProfile(UUID authUserId) {
        return profiles.findByAuthUserId(authUserId)
                .orElseThrow(() -> new UserException(UserErrorCode.PROFILE_NOT_FOUND));
    }
}
