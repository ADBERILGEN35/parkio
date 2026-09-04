package com.parkio.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.user.application.port.RecentDestinationRepository;
import com.parkio.user.application.port.RecentParkingRepository;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.PlaceIdentity;
import com.parkio.user.domain.place.RecentDestination;
import com.parkio.user.domain.place.RecentParking;
import com.parkio.user.domain.place.RecentParkingTargetKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecentApplicationServiceTest {

    private static final UUID AUTH = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000051");
    private static final UUID PROFILE = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000052");
    private static final UUID FACILITY = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000053");
    private static final Instant NOW = Instant.parse("2026-08-06T15:00:00Z");

    private RecentDestinationRepository destinationRepo;
    private RecentParkingRepository parkingRepo;
    private UserProfileRepository profiles;
    private RecentDestinationApplicationService destinations;
    private RecentParkingApplicationService parking;

    @BeforeEach
    void setUp() {
        destinationRepo = mock(RecentDestinationRepository.class);
        parkingRepo = mock(RecentParkingRepository.class);
        profiles = mock(UserProfileRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        when(profiles.findByAuthUserId(AUTH)).thenReturn(Optional.of(profile()));
        destinations = new RecentDestinationApplicationService(destinationRepo, profiles, clock, 3);
        parking = new RecentParkingApplicationService(parkingRepo, profiles, clock, 3);
    }

    @Test
    void confirmCreatesThenUpdatesRecencyOnRepeat() {
        when(destinationRepo.findByUserProfileIdAndDuplicateKey(eq(PROFILE), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(destinationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(destinationRepo.findAllByUserProfileIdOrderByLastUsedAtDesc(PROFILE)).thenReturn(List.of());

        RecentDestination created = destinations.confirm(
                AUTH, "Kordon", 38.43, 27.14, PlaceDestinationSource.MAP_PIN, null, null);
        assertThat(created.useCount()).isEqualTo(1);

        RecentDestination existing = RecentDestination.create(
                PROFILE, "Kordon", 38.43, 27.14, PlaceDestinationSource.MAP_PIN, null, null, NOW.minusSeconds(30));
        when(destinationRepo.findByUserProfileIdAndDuplicateKey(eq(PROFILE), any()))
                .thenReturn(Optional.of(existing));
        when(destinationRepo.findAllByUserProfileIdOrderByLastUsedAtDesc(PROFILE)).thenReturn(List.of(existing));

        RecentDestination updated = destinations.confirm(
                AUTH, "Kordon Alsancak", 38.43, 27.14, PlaceDestinationSource.MAP_PIN, null, "İzmir");
        assertThat(updated.useCount()).isEqualTo(2);
        assertThat(updated.label()).isEqualTo("Kordon Alsancak");
        assertThat(updated.lastUsedAt()).isEqualTo(NOW);
    }

    @Test
    void confirmPrunesOldestBeyondLimit() {
        when(destinationRepo.findByUserProfileIdAndDuplicateKey(eq(PROFILE), any()))
                .thenReturn(Optional.empty());
        when(destinationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<RecentDestination> afterSave = new ArrayList<>();
        RecentDestination newest = RecentDestination.create(
                PROFILE, "New", 38.5, 27.2, PlaceDestinationSource.MAP_PIN, null, null, NOW);
        afterSave.add(newest);
        afterSave.add(RecentDestination.create(
                PROFILE, "A", 38.1, 27.1, PlaceDestinationSource.MAP_PIN, null, null, NOW.minusSeconds(10)));
        afterSave.add(RecentDestination.create(
                PROFILE, "B", 38.2, 27.1, PlaceDestinationSource.MAP_PIN, null, null, NOW.minusSeconds(20)));
        RecentDestination oldest = RecentDestination.create(
                PROFILE, "C", 38.3, 27.1, PlaceDestinationSource.MAP_PIN, null, null, NOW.minusSeconds(30));
        afterSave.add(oldest);
        when(destinationRepo.findAllByUserProfileIdOrderByLastUsedAtDesc(PROFILE)).thenReturn(afterSave);

        destinations.confirm(AUTH, "New", 38.5, 27.2, PlaceDestinationSource.MAP_PIN, null, null);
        verify(destinationRepo).deleteByIdAndUserProfileId(oldest.id(), PROFILE);
    }

    @Test
    void destinationDeleteRequiresOwnership() {
        when(destinationRepo.findByIdAndUserProfileId(any(), eq(PROFILE))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> destinations.delete(AUTH, UUID.randomUUID()))
                .isInstanceOf(UserException.class)
                .extracting(ex -> ((UserException) ex).errorCode())
                .isEqualTo(UserErrorCode.RECENT_DESTINATION_NOT_FOUND);
        verify(destinationRepo, never()).deleteByIdAndUserProfileId(any(), any());
    }

    @Test
    void parkingRecordIsIdempotentAndRejectsUnsupportedKind() {
        when(parkingRepo.findByUserProfileIdAndTarget(
                        PROFILE, RecentParkingTargetKind.MUNICIPAL_FACILITY, FACILITY))
                .thenReturn(Optional.empty());
        when(parkingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(parkingRepo.findAllByUserProfileIdOrderByLastUsedAtDesc(PROFILE)).thenReturn(List.of());

        RecentParking created = parking.record(AUTH, RecentParkingTargetKind.MUNICIPAL_FACILITY, FACILITY);
        assertThat(created.useCount()).isEqualTo(1);

        assertThatThrownBy(() -> parking.record(AUTH, null, FACILITY))
                .isInstanceOf(UserException.class)
                .extracting(ex -> ((UserException) ex).errorCode())
                .isEqualTo(UserErrorCode.UNSUPPORTED_RECENT_PARKING_TARGET);
    }

    @Test
    void clearAllDeletesUserScopedHistoryOnly() {
        destinations.clearAll(AUTH);
        parking.clearAll(AUTH);
        verify(destinationRepo).deleteAllByUserProfileId(PROFILE);
        verify(parkingRepo).deleteAllByUserProfileId(PROFILE);
    }

    @Test
    void identityPreferredOverCoordinates() {
        PlaceIdentity identity = PlaceIdentity.of("osm-nominatim", "N9");
        ArgumentCaptor<RecentDestination> captor = ArgumentCaptor.forClass(RecentDestination.class);
        when(destinationRepo.findByUserProfileIdAndDuplicateKey(eq(PROFILE), any()))
                .thenReturn(Optional.empty());
        when(destinationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(destinationRepo.findAllByUserProfileIdOrderByLastUsedAtDesc(PROFILE)).thenReturn(List.of());

        destinations.confirm(
                AUTH, "Place", 38.43, 27.14, PlaceDestinationSource.GEOCODING, identity, null);
        verify(destinationRepo).save(captor.capture());
        assertThat(captor.getValue().duplicateKey()).isEqualTo("identity:osm-nominatim:N9");
    }

    private static UserProfile profile() {
        return new UserProfile(
                PROFILE,
                AUTH,
                "tester@example.com",
                "Tester",
                null,
                null,
                com.parkio.user.domain.UserStatus.ACTIVE,
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                0L);
    }
}
