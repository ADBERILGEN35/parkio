package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.PublicExploreQueryService;
import com.parkio.parking.application.RegistryPublicationService;
import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.application.port.RoadsideDiscoveryQueryPort;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.infrastructure.config.IzelmanProperties;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.config.PublicExploreProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Public Explore HTTP/JSON contract for İSPARK closed-occupancy publication.
 * Uses the real query service and mapper, not a stubbed view.
 */
class IsparkClosedOccupancyPublicExploreHttpTest {
    private static final Instant NOW = Instant.parse("2026-09-25T07:14:19Z");
    private static final UUID AVCILAR_IDO = UUID.fromString("81279bd3-5c60-42a1-81bc-8255e22a1a48");
    private static final Set<String> ISPARK_KEYS = Set.of(MunicipalSourceIdentity.ISPARK);

    @Test
    void publicExploreOmitsSpacesForClosedIsparkWithPositiveEmptyCapacity() throws Exception {
        MockMvc mvc = publicMvc(closedAvcilar());

        mvc.perform(get("/api/v1/public/explore/facilities")
                        .param("lat", "40.9712")
                        .param("lng", "28.7185")
                        .param("radiusMeters", "5000")
                        .param("limit", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facilities.length()").value(1))
                .andExpect(jsonPath("$.municipalTotalInScope").value(1))
                .andExpect(jsonPath("$.facilities[0].id").value(AVCILAR_IDO.toString()))
                .andExpect(jsonPath("$.facilities[0].displayName").value("Avcılar İdo"))
                .andExpect(jsonPath("$.facilities[0].capacityTotal").value(270))
                .andExpect(jsonPath("$.facilities[0].availabilityFreshness").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.facilities[0].availableSpaces").value((Object) null));
    }

    @Test
    void publicExplorePreservesOpenIsparkZeroAndPositiveAvailability() throws Exception {
        UUID openId = UUID.fromString("00000000-0000-0000-0000-000000000801");
        UUID zeroId = UUID.fromString("00000000-0000-0000-0000-000000000802");
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        when(facilities.countPublicExploreNearby(40.99, 29.03, 5_000, ISPARK_KEYS)).thenReturn(2L);
        when(facilities.publicExploreNearby(40.99, 29.03, 5_000, 6, ISPARK_KEYS)).thenReturn(List.of(
                isparkFacility(openId, "Open lot", 40.9901, 29.0292, 120, "{\"isOpen\":1}"),
                isparkFacility(zeroId, "Full lot", 40.9902, 29.0293, 50, "{\"isOpen\":1}")));
        when(snapshots.latestForFacilityAndSourceKey(openId, MunicipalSourceIdentity.ISPARK))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        120, 40, 80, NOW.minusSeconds(5), 5L, true)));
        when(snapshots.latestForFacilityAndSourceKey(zeroId, MunicipalSourceIdentity.ISPARK))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        50, 50, 0, NOW.minusSeconds(5), 5L, true)));

        MockMvc mvc = publicMvc(facilities, snapshots);
        String body = mvc.perform(get("/api/v1/public/explore/facilities")
                        .param("lat", "40.99")
                        .param("lng", "29.03")
                        .param("radiusMeters", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facilities.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(jsonList(body, "$.facilities[?(@.id=='" + openId + "')].availableSpaces")).isEqualTo(java.util.List.of(80));
        assertThat(jsonList(body, "$.facilities[?(@.id=='" + openId + "')].availabilityFreshness")).isEqualTo(java.util.List.of("LIVE"));
        assertThat(jsonList(body, "$.facilities[?(@.id=='" + zeroId + "')].availableSpaces")).isEqualTo(java.util.List.of(0));
        assertThat(jsonList(body, "$.facilities[?(@.id=='" + zeroId + "')].availabilityFreshness")).isEqualTo(java.util.List.of("LIVE"));
    }

    @Test
    void authenticatedMunicipalResponseSharesClosedOccupancySuppression() throws Exception {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        var closed = closedAvcilar();
        when(facilities.findById(AVCILAR_IDO)).thenReturn(Optional.of(closed));
        when(facilities.nearby(40.9712, 28.7185, 1000, 100)).thenReturn(List.of(closed));
        when(snapshots.latestForFacility(AVCILAR_IDO)).thenReturn(Optional.of(
                new MunicipalOccupancySnapshotRepository.Snapshot(
                        270, 7, 263, NOW.minusSeconds(5), 5L, true)));
        MunicipalSourceProperties municipal = new MunicipalSourceProperties();
        municipal.getDiscovery().setDuplicatePresentationEnabled(false);
        MunicipalFacilityQueryService service = new MunicipalFacilityQueryService(
                facilities,
                snapshots,
                municipal,
                new IzelmanProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RegistryPublicationService registry = mock(RegistryPublicationService.class);
        when(registry.forFacility(any())).thenReturn(RegistryPublicationService.Enrichment.hidden());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MunicipalFacilityController(service, registry))
                .build();

        mvc.perform(get("/api/v1/parking/facilities/{id}", AVCILAR_IDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(AVCILAR_IDO.toString()))
                .andExpect(jsonPath("$.freshness").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.availableSpaces").value((Object) null))
                .andExpect(jsonPath("$.occupiedSpaces").value((Object) null));
        mvc.perform(get("/api/v1/parking/facilities/nearby")
                        .param("lat", "40.9712")
                        .param("lng", "28.7185"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(AVCILAR_IDO.toString()))
                .andExpect(jsonPath("$[0].freshness").value("UNAVAILABLE"))
                .andExpect(jsonPath("$[0].availableSpaces").value((Object) null));
    }

    private static MockMvc publicMvc(MunicipalFacilityRepository.Facility facility) {
        var facilities = mock(MunicipalFacilityRepository.class);
        var snapshots = mock(MunicipalOccupancySnapshotRepository.class);
        when(facilities.countPublicExploreNearby(anyDouble(), anyDouble(), anyInt(), eq(ISPARK_KEYS)))
                .thenReturn(1L);
        when(facilities.publicExploreNearby(anyDouble(), anyDouble(), anyInt(), anyInt(), eq(ISPARK_KEYS)))
                .thenReturn(List.of(facility));
        when(snapshots.latestForFacilityAndSourceKey(facility.id(), MunicipalSourceIdentity.ISPARK))
                .thenReturn(Optional.of(new MunicipalOccupancySnapshotRepository.Snapshot(
                        270, 7, 263, NOW.minusSeconds(5), 5L, true)));
        return publicMvc(facilities, snapshots);
    }

    private static MockMvc publicMvc(
            MunicipalFacilityRepository facilities, MunicipalOccupancySnapshotRepository snapshots) {
        PublicExploreProperties props = new PublicExploreProperties();
        props.setEnabled(true);
        props.setAllowedSourceFamilies(List.of("ISPARK"));
        PublicExploreQueryService service = new PublicExploreQueryService(
                facilities,
                snapshots,
                mock(RoadsideDiscoveryQueryPort.class),
                props,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return MockMvcBuilders.standaloneSetup(new PublicExploreController(service)).build();
    }

    private static MunicipalFacilityRepository.Facility closedAvcilar() {
        return isparkFacility(AVCILAR_IDO, "Avcılar İdo", 40.9712, 28.7185, 270, "{\"isOpen\":0}");
    }

    private static MunicipalFacilityRepository.Facility isparkFacility(
            UUID id, String name, double lat, double lng, Integer capacity, String metadata) {
        return new MunicipalFacilityRepository.Facility(
                id, name, "İSPARK", MunicipalFacilityType.OFF_STREET, "AVCILAR", lat, lng,
                capacity, true, true, "ignored", "ignored", 300, 900,
                MunicipalSourceIdentity.ISPARK, Set.of(MunicipalSourceIdentity.ISPARK),
                MunicipalAccessClassification.PUBLIC, metadata);
    }

    private static java.util.List<?> jsonList(String body, String path) {
        return JsonPath.read(body, path);
    }
}
