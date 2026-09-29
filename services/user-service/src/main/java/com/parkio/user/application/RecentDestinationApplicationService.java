package com.parkio.user.application;

import com.parkio.user.application.port.RecentDestinationRepository;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.PlaceIdentity;
import com.parkio.user.domain.place.RecentDestination;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recent destinations (WP-SPA-07). Confirm/upsert only — no raw query history.
 */
@Service
@Transactional
public class RecentDestinationApplicationService {

    private final RecentDestinationRepository recents;
    private final UserProfileRepository profiles;
    private final Clock clock;
    private final int maxPerUser;

    public RecentDestinationApplicationService(
            RecentDestinationRepository recents,
            UserProfileRepository profiles,
            Clock clock,
            @Value("${parkio.spa.recents.destination-limit:20}") int maxPerUser) {
        this.recents = recents;
        this.profiles = profiles;
        this.clock = clock;
        this.maxPerUser = maxPerUser > 0 ? maxPerUser : RecentDestination.DEFAULT_MAX_PER_USER;
    }

    @Transactional(readOnly = true)
    public List<RecentDestination> list(UUID authUserId) {
        return recents.findAllByUserProfileIdOrderByLastUsedAtDesc(requireProfile(authUserId).id());
    }

    public RecentDestination confirm(
            UUID authUserId,
            String label,
            double latitude,
            double longitude,
            PlaceDestinationSource source,
            PlaceIdentity placeIdentity,
            String subtitle) {
        UUID profileId = requireProfile(authUserId).id();
        RecentDestination candidate = RecentDestination.create(
                profileId, label, latitude, longitude, source, placeIdentity, subtitle, clock.instant());
        var existing = recents.findByUserProfileIdAndDuplicateKey(profileId, candidate.duplicateKey());
        if (existing.isPresent()) {
            RecentDestination current = existing.get();
            current.recordConfirmation(label, subtitle, clock.instant());
            return pruneAfter(recents.save(current), profileId);
        }
        try {
            return pruneAfter(recents.save(candidate), profileId);
        } catch (DataIntegrityViolationException ex) {
            RecentDestination raced = recents.findByUserProfileIdAndDuplicateKey(profileId, candidate.duplicateKey())
                    .orElseThrow(() -> new UserException(UserErrorCode.INVALID_RECENT_DESTINATION));
            raced.recordConfirmation(label, subtitle, clock.instant());
            return pruneAfter(recents.save(raced), profileId);
        }
    }

    public void delete(UUID authUserId, UUID recentId) {
        UUID profileId = requireProfile(authUserId).id();
        RecentDestination existing = recents.findByIdAndUserProfileId(recentId, profileId)
                .orElseThrow(() -> new UserException(UserErrorCode.RECENT_DESTINATION_NOT_FOUND));
        recents.deleteByIdAndUserProfileId(existing.id(), profileId);
    }

    public void clearAll(UUID authUserId) {
        UUID profileId = requireProfile(authUserId).id();
        recents.deleteAllByUserProfileId(profileId);
    }

    private RecentDestination pruneAfter(RecentDestination kept, UUID profileId) {
        List<RecentDestination> ordered = recents.findAllByUserProfileIdOrderByLastUsedAtDesc(profileId);
        if (ordered.size() <= maxPerUser) {
            return kept;
        }
        for (int i = maxPerUser; i < ordered.size(); i++) {
            RecentDestination excess = ordered.get(i);
            recents.deleteByIdAndUserProfileId(excess.id(), profileId);
        }
        return kept;
    }

    private UserProfile requireProfile(UUID authUserId) {
        return profiles.findByAuthUserId(authUserId)
                .orElseThrow(() -> new UserException(UserErrorCode.PROFILE_NOT_FOUND));
    }
}
