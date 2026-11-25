package com.parkio.user.application;

import com.parkio.user.application.port.UserOwnerFence;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller's profile under the erasure fence for saved places, favourites and
 * recents (PA30-S01, #597). Every method of those services that is keyed by the caller's
 * {@code authUserId} resolves the profile here, reads included, so they follow the same rule
 * as the profile paths in {@link UserApplicationService}:
 * <ul>
 *   <li>A child write that arrives while an erasure holds the exclusive lock waits, then sees
 *       the tombstone and fails with {@code PROFILE_NOT_FOUND} instead of writing a row.</li>
 *   <li>A child write already past the fence makes the erasure wait until it commits, so the
 *       erasure deletes that row. Without the fence the erasure failed on the
 *       {@code REFERENCES user_profiles} foreign key (NO ACTION) and needed a Kafka
 *       redelivery.</li>
 * </ul>
 * The foreign keys still keep child rows from outliving the profile. The methods keyed by a
 * profile id do not come through here; {@link SavedPlaceApplicationService} documents them.
 */
@Component
public class FencedProfileLookup {

    private final UserOwnerFence ownerFence;
    private final UserProfileRepository profiles;

    public FencedProfileLookup(UserOwnerFence ownerFence, UserProfileRepository profiles) {
        this.ownerFence = Objects.requireNonNull(ownerFence, "ownerFence");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    /**
     * Joins the shared side of the fence for the caller's transaction, which holds it until it
     * ends, and returns the profile. Throws {@code PROFILE_NOT_FOUND} for an erased user or a
     * missing profile.
     */
    public UserProfile requireProfile(UUID authUserId) {
        if (!ownerFence.admitWrite(authUserId)) {
            throw new UserException(UserErrorCode.PROFILE_NOT_FOUND);
        }
        return profiles.findByAuthUserId(authUserId)
                .orElseThrow(() -> new UserException(UserErrorCode.PROFILE_NOT_FOUND));
    }
}
