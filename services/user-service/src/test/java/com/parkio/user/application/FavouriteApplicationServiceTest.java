package com.parkio.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.user.application.port.FavouriteDestinationRepository;
import com.parkio.user.application.port.FavouriteParkingRepository;
import com.parkio.user.application.port.UserOwnerFence;
import com.parkio.user.application.port.UserProfileRepository;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.UserStatus;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import com.parkio.user.domain.place.FavouriteDestination;
import com.parkio.user.domain.place.FavouriteParking;
import com.parkio.user.domain.place.FavouriteParkingTargetKind;
import com.parkio.user.domain.place.PlaceDestinationSource;
import com.parkio.user.domain.place.PlaceIdentity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class FavouriteApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID AUTH = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID PROFILE = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID OTHER_AUTH = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID OTHER_PROFILE = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    private static final UUID FACILITY = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");

    private FakeProfiles profiles;
    private UserOwnerFence fence;
    private FakeParkingFavourites parkingRepo;
    private FakeDestinationFavourites destinationRepo;
    private FavouriteParkingApplicationService parkingService;
    private FavouriteDestinationApplicationService destinationService;
    private AtomicLong epochSecond;

    @BeforeEach
    void setUp() {
        profiles = new FakeProfiles();
        parkingRepo = new FakeParkingFavourites();
        destinationRepo = new FakeDestinationFavourites();
        profiles.put(new UserProfile(
                PROFILE, AUTH, "a@example.com", "Alice", null, null, UserStatus.ACTIVE, null, NOW, 0L));
        profiles.put(new UserProfile(
                OTHER_PROFILE, OTHER_AUTH, "b@example.com", "Bob", null, null, UserStatus.ACTIVE, null, NOW, 0L));
        epochSecond = new AtomicLong(NOW.getEpochSecond());
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochSecond(epochSecond.getAndIncrement());
            }
        };
        fence = mock(UserOwnerFence.class);
        when(fence.admitWrite(any())).thenReturn(true);
        FencedProfileLookup fencedProfiles = new FencedProfileLookup(fence, profiles);
        parkingService = new FavouriteParkingApplicationService(parkingRepo, fencedProfiles, clock);
        destinationService = new FavouriteDestinationApplicationService(destinationRepo, fencedProfiles, clock);
    }

    @Test
    void everyCallerPathIsRefusedByTheErasureFenceBeforeReadingOrWritingFavourites() {
        FavouriteDestination kept = destinationService.add(
                AUTH, "Kept", 41.0, 29.0, PlaceDestinationSource.MAP_PIN, null, null);
        parkingService.addMunicipalFacility(AUTH, FACILITY);
        when(fence.admitWrite(AUTH)).thenReturn(false);
        List<ThrowingCallable> paths = List.of(
                () -> parkingService.list(AUTH),
                () -> parkingService.addMunicipalFacility(AUTH, UUID.randomUUID()),
                () -> parkingService.removeMunicipalFacility(AUTH, FACILITY),
                () -> parkingService.statusFor(AUTH, List.of(FACILITY)),
                () -> destinationService.list(AUTH),
                () -> destinationService.add(AUTH, "New", 41.1, 29.1, PlaceDestinationSource.MAP_PIN, null, null),
                () -> destinationService.updateDisplay(AUTH, kept.id(), "Renamed", null),
                () -> destinationService.delete(AUTH, kept.id()));
        for (ThrowingCallable path : paths) {
            assertThatThrownBy(path).isInstanceOfSatisfying(UserException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(UserErrorCode.PROFILE_NOT_FOUND));
        }
        verify(fence, times(paths.size() + 2)).admitWrite(AUTH);
        assertThat(parkingRepo.findAllByUserProfileId(PROFILE)).extracting(FavouriteParking::targetId)
                .containsExactly(FACILITY);
        assertThat(destinationRepo.findAllByUserProfileId(PROFILE)).extracting(FavouriteDestination::label)
                .containsExactly("Kept");
    }

    @Test
    void parkingAddIsIdempotent() {
        FavouriteParking first = parkingService.addMunicipalFacility(AUTH, FACILITY);
        FavouriteParking second = parkingService.addMunicipalFacility(AUTH, FACILITY);
        assertThat(first.id()).isEqualTo(second.id());
        assertThat(parkingRepo.findAllByUserProfileId(PROFILE)).hasSize(1);
    }

    @Test
    void parkingConcurrentAddReturnsExisting() {
        parkingRepo.failFirstInsert = true;
        FavouriteParking fav = parkingService.addMunicipalFacility(AUTH, FACILITY);
        assertThat(fav.targetId()).isEqualTo(FACILITY);
        assertThat(parkingRepo.findAllByUserProfileId(PROFILE)).hasSize(1);
    }

    @Test
    void parkingCrossUserIsolationOnDelete() {
        parkingService.addMunicipalFacility(AUTH, FACILITY);
        assertThatThrownBy(() -> parkingService.removeMunicipalFacility(OTHER_AUTH, FACILITY))
                .isInstanceOf(UserException.class)
                .extracting(ex -> ((UserException) ex).errorCode())
                .isEqualTo(UserErrorCode.FAVOURITE_PARKING_NOT_FOUND);
    }

    @Test
    void parkingStatusBatch() {
        parkingService.addMunicipalFacility(AUTH, FACILITY);
        UUID other = UUID.fromString("11111111-1111-4111-8111-111111111111");
        assertThat(parkingService.statusFor(AUTH, List.of(FACILITY, other))).containsExactly(FACILITY);
    }

    @Test
    void destinationAddIdempotentByCoords() {
        FavouriteDestination first = destinationService.add(
                AUTH, "Kordon", 38.43, 27.14, PlaceDestinationSource.MAP_PIN, null, null);
        FavouriteDestination second = destinationService.add(
                AUTH, "Different label", 38.43, 27.14, PlaceDestinationSource.MAP_PIN, null, null);
        assertThat(first.id()).isEqualTo(second.id());
        assertThat(destinationRepo.findAllByUserProfileId(PROFILE)).hasSize(1);
    }

    @Test
    void destinationIdentityPreventsCoordDuplicateAcrossLabels() {
        PlaceIdentity identity = PlaceIdentity.of("osm-nominatim", "N1");
        FavouriteDestination first = destinationService.add(
                AUTH, "A", 38.0, 27.0, PlaceDestinationSource.GEOCODING, identity, null);
        FavouriteDestination second = destinationService.add(
                AUTH, "B", 39.0, 28.0, PlaceDestinationSource.GEOCODING, identity, null);
        assertThat(first.id()).isEqualTo(second.id());
    }

    @Test
    void destinationUpdateAndDeleteOwnerScoped() {
        FavouriteDestination fav = destinationService.add(
                AUTH, "Forum", 38.45, 27.21, PlaceDestinationSource.GEOCODING, null, null);
        FavouriteDestination updated = destinationService.updateDisplay(AUTH, fav.id(), "Forum Bornova", "Bornova");
        assertThat(updated.label()).isEqualTo("Forum Bornova");
        assertThatThrownBy(() -> destinationService.delete(OTHER_AUTH, fav.id()))
                .isInstanceOf(UserException.class)
                .extracting(ex -> ((UserException) ex).errorCode())
                .isEqualTo(UserErrorCode.FAVOURITE_DESTINATION_NOT_FOUND);
        destinationService.delete(AUTH, fav.id());
        assertThat(destinationService.list(AUTH)).isEmpty();
    }

    @Test
    void destinationListOrdersByUpdatedAtDesc() {
        FavouriteDestination older = destinationService.add(
                AUTH, "Old", 38.1, 27.1, PlaceDestinationSource.MAP_PIN, null, null);
        FavouriteDestination newer = destinationService.add(
                AUTH, "New", 38.2, 27.2, PlaceDestinationSource.MAP_PIN, null, null);
        destinationService.updateDisplay(AUTH, older.id(), "Old Updated", null);
        List<FavouriteDestination> listed = destinationService.list(AUTH);
        assertThat(listed.get(0).id()).isEqualTo(older.id());
        assertThat(listed.get(1).id()).isEqualTo(newer.id());
    }

    private static final class FakeProfiles implements UserProfileRepository {
        private final Map<UUID, UserProfile> byAuth = new HashMap<>();
        private final Map<UUID, UserProfile> byId = new HashMap<>();

        void put(UserProfile profile) {
            byAuth.put(profile.authUserId(), profile);
            byId.put(profile.id(), profile);
        }

        @Override
        public UserProfile save(UserProfile profile) {
            put(profile);
            return profile;
        }

        @Override
        public Optional<UserProfile> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<UserProfile> findByAuthUserId(UUID authUserId) {
            return Optional.ofNullable(byAuth.get(authUserId));
        }

        @Override
        public boolean existsByAuthUserId(UUID authUserId) {
            return byAuth.containsKey(authUserId);
        }
    }

    private static final class FakeParkingFavourites implements FavouriteParkingRepository {
        private final Map<UUID, FavouriteParking> byId = new HashMap<>();
        private boolean failFirstInsert;
        private final AtomicBoolean firstInsertDone = new AtomicBoolean();

        @Override
        public FavouriteParking save(FavouriteParking favourite) {
            boolean isNew = !byId.containsKey(favourite.id());
            if (isNew) {
                Optional<FavouriteParking> existing = findByUserProfileIdAndTarget(
                        favourite.userProfileId(), favourite.targetKind(), favourite.targetId());
                if (existing.isPresent()) {
                    throw new DataIntegrityViolationException("dup");
                }
                if (failFirstInsert && firstInsertDone.compareAndSet(false, true)) {
                    FavouriteParking raced = FavouriteParking.create(
                            favourite.userProfileId(),
                            favourite.targetKind(),
                            favourite.targetId(),
                            favourite.createdAt());
                    byId.put(raced.id(), raced);
                    throw new DataIntegrityViolationException("race");
                }
            }
            byId.put(favourite.id(), favourite);
            return favourite;
        }

        @Override
        public Optional<FavouriteParking> findByUserProfileIdAndTarget(
                UUID userProfileId, FavouriteParkingTargetKind targetKind, UUID targetId) {
            return byId.values().stream()
                    .filter(f -> f.userProfileId().equals(userProfileId)
                            && f.targetKind() == targetKind
                            && f.targetId().equals(targetId))
                    .findFirst();
        }

        @Override
        public List<FavouriteParking> findAllByUserProfileId(UUID userProfileId) {
            return byId.values().stream()
                    .filter(f -> f.userProfileId().equals(userProfileId))
                    .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                    .toList();
        }

        @Override
        public long countByUserProfileId(UUID userProfileId) {
            return findAllByUserProfileId(userProfileId).size();
        }

        @Override
        public void deleteByUserProfileIdAndTarget(
                UUID userProfileId, FavouriteParkingTargetKind targetKind, UUID targetId) {
            findByUserProfileIdAndTarget(userProfileId, targetKind, targetId)
                    .ifPresent(f -> byId.remove(f.id()));
        }

        @Override
        public List<FavouriteParking> findByUserProfileIdAndTargets(
                UUID userProfileId, FavouriteParkingTargetKind targetKind, Collection<UUID> targetIds) {
            return byId.values().stream()
                    .filter(f -> f.userProfileId().equals(userProfileId)
                            && f.targetKind() == targetKind
                            && targetIds.contains(f.targetId()))
                    .toList();
        }
    }

    private static final class FakeDestinationFavourites implements FavouriteDestinationRepository {
        private final Map<UUID, FavouriteDestination> byId = new HashMap<>();

        @Override
        public FavouriteDestination save(FavouriteDestination favourite) {
            Optional<FavouriteDestination> dup = findByUserProfileIdAndDuplicateKey(
                    favourite.userProfileId(), favourite.duplicateKey());
            if (dup.isPresent() && !dup.get().id().equals(favourite.id())) {
                throw new DataIntegrityViolationException("dup");
            }
            byId.put(favourite.id(), favourite);
            return favourite;
        }

        @Override
        public Optional<FavouriteDestination> findByIdAndUserProfileId(UUID id, UUID userProfileId) {
            return Optional.ofNullable(byId.get(id))
                    .filter(f -> f.userProfileId().equals(userProfileId));
        }

        @Override
        public Optional<FavouriteDestination> findByUserProfileIdAndDuplicateKey(
                UUID userProfileId, String duplicateKey) {
            return byId.values().stream()
                    .filter(f -> f.userProfileId().equals(userProfileId) && f.duplicateKey().equals(duplicateKey))
                    .findFirst();
        }

        @Override
        public List<FavouriteDestination> findAllByUserProfileId(UUID userProfileId) {
            return byId.values().stream()
                    .filter(f -> f.userProfileId().equals(userProfileId))
                    .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt()))
                    .toList();
        }

        @Override
        public long countByUserProfileId(UUID userProfileId) {
            return findAllByUserProfileId(userProfileId).size();
        }

        @Override
        public void deleteByIdAndUserProfileId(UUID id, UUID userProfileId) {
            findByIdAndUserProfileId(id, userProfileId).ifPresent(f -> byId.remove(f.id()));
        }
    }
}
