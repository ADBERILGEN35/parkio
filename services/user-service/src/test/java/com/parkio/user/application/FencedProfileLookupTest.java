package com.parkio.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.parkio.user.application.port.UserOwnerFence;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.UserStatus;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FencedProfileLookupTest {

    private static final UUID AUTH = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000061");
    private static final UUID PROFILE = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000062");

    private final UserOwnerFence fence = mock(UserOwnerFence.class);
    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final FencedProfileLookup lookup = new FencedProfileLookup(fence, profiles);

    @Test
    void joinsTheFenceBeforeReadingTheProfile() {
        UserProfile profile = new UserProfile(
                PROFILE, AUTH, "a@example.test", "Synthetic", null, null, UserStatus.ACTIVE, null, Instant.EPOCH, 0L);
        when(fence.admitWrite(AUTH)).thenReturn(true);
        when(profiles.findByAuthUserId(AUTH)).thenReturn(Optional.of(profile));

        assertThat(lookup.requireProfile(AUTH)).isSameAs(profile);
        var order = inOrder(fence, profiles);
        order.verify(fence).admitWrite(AUTH);
        order.verify(profiles).findByAuthUserId(AUTH);
    }

    @Test
    void erasedUserIsNotFoundWithoutReadingTheProfile() {
        when(fence.admitWrite(AUTH)).thenReturn(false);

        assertThatThrownBy(() -> lookup.requireProfile(AUTH)).isInstanceOfSatisfying(UserException.class,
                e -> assertThat(e.errorCode()).isEqualTo(UserErrorCode.PROFILE_NOT_FOUND));
        verifyNoInteractions(profiles);
    }

    @Test
    void admittedUserWithoutAProfileIsNotFound() {
        when(fence.admitWrite(AUTH)).thenReturn(true);
        when(profiles.findByAuthUserId(AUTH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookup.requireProfile(AUTH)).isInstanceOfSatisfying(UserException.class,
                e -> assertThat(e.errorCode()).isEqualTo(UserErrorCode.PROFILE_NOT_FOUND));
    }
}
