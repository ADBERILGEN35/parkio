package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.MunicipalFacilityQueryService;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import com.parkio.parking.infrastructure.persistence.MunicipalOccupancySnapshotRepositoryAdapter;
import com.parkio.parking.testsupport.PostgisTestImages;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * F-38R: facility-list occupancy uses a bounded batch query — call counts against
 * the occupancy port stay independent of facility count, while mixed-source
 * publication / freshness output is preserved.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MunicipalOccupancyBatchQueryIT {
    static final AtomicInteger SINGLE_FACILITY_CALLS = new AtomicInteger();
    static final AtomicInteger BATCH_FACILITY_CALLS = new AtomicInteger();
    static final AtomicInteger BATCH_SOURCE_CALLS = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgisTestImages.dockerImageName())
            .withDatabaseName("parkio_occ_batch_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @TestConfiguration
    static class CountingOccupancyConfig {
        @Bean
        @Primary
        MunicipalOccupancySnapshotRepository countingSnapshots(
                MunicipalOccupancySnapshotRepositoryAdapter delegate) {
            return new CountingOccupancySnapshots(delegate);
        }
    }

    static final class CountingOccupancySnapshots implements MunicipalOccupancySnapshotRepository {
        private final MunicipalOccupancySnapshotRepository delegate;

        CountingOccupancySnapshots(MunicipalOccupancySnapshotRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean insertIfAbsent(UUID facilityId, UUID sourceId, UUID sourceLinkId,
                UUID syncRunId, NormalizedMunicipalOccupancy occupancy) {
            return delegate.insertIfAbsent(facilityId, sourceId, sourceLinkId, syncRunId, occupancy);
        }

        @Override
        public Optional<Snapshot> latestForFacility(UUID facilityId) {
            SINGLE_FACILITY_CALLS.incrementAndGet();
            return delegate.latestForFacility(facilityId);
        }

        @Override
        public Optional<Snapshot> latestForFacilityAndSourceKey(UUID facilityId, String sourceKey) {
            SINGLE_FACILITY_CALLS.incrementAndGet();
            return delegate.latestForFacilityAndSourceKey(facilityId, sourceKey);
        }

        @Override
        public Map<UUID, Snapshot> latestForFacilities(Collection<UUID> facilityIds) {
            BATCH_FACILITY_CALLS.incrementAndGet();
            return delegate.latestForFacilities(facilityIds);
        }

        @Override
        public Map<UUID, Snapshot> latestForFacilitiesAndSourceKey(
                Collection<UUID> facilityIds, String sourceKey) {
            BATCH_SOURCE_CALLS.incrementAndGet();
            return delegate.latestForFacilitiesAndSourceKey(facilityIds, sourceKey);
        }

        @Override
        public Optional<Snapshot> latestForSource(UUID sourceId) {
            return delegate.latestForSource(sourceId);
        }

        @Override
        public Map<String, PreviousObservation> latestRunObservations(UUID sourceId) {
            return delegate.latestRunObservations(sourceId);
        }

        @Override
        public long count() {
            return delegate.count();
        }

        @Override
        public long countExpiredExcludingLatest(Instant cutoff) {
            return delegate.countExpiredExcludingLatest(cutoff);
        }

        @Override
        public int deleteExpiredExcludingLatest(Instant cutoff, int batchSize) {
            return delegate.deleteExpiredExcludingLatest(cutoff, batchSize);
        }
    }

    @Autowired MunicipalFacilityQueryService query;
    @Autowired JdbcClient jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.kafka.ai-validation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.moderation-timeout.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("parkio.municipal.enabled", () -> "true");
        registry.add("parkio.municipal.osm.publication-enabled", () -> "true");
        registry.add("parkio.municipal.discovery.duplicate-presentation-enabled", () -> "false");
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM municipal_occupancy_snapshots").update();
        jdbc.sql("DELETE FROM municipal_facility_source_links").update();
        jdbc.sql("DELETE FROM municipal_parking_facilities").update();
        SINGLE_FACILITY_CALLS.set(0);
        BATCH_FACILITY_CALLS.set(0);
        BATCH_SOURCE_CALLS.set(0);
    }

    @Test
    void occupancySelectCountIndependentOfFacilityCountAndMixedSourcesPreserveOutput() {
        int n = 12;
        for (int i = 0; i < n; i++) {
            UUID facilityId = UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i + 1));
            boolean useOsm = i % 3 == 0;
            String sourceKey = useOsm ? MunicipalSourceIdentity.OSM : MunicipalSourceIdentity.IZUM;
            double lat = 38.40;
            double lng = 27.10 + (i * 0.001);
            insertFacility(facilityId, "F-" + i, lat, lng, 100);
            insertLink(facilityId, sourceKey, "ext-" + i);
            jdbc.sql("""
                    UPDATE municipal_parking_facilities SET primary_source_key = :key WHERE id = :id
                    """).param("key", sourceKey).param("id", facilityId).update();
            UUID syncRunId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO municipal_source_sync_runs(
                      id, source_id, correlation_id, started_at, completed_at, status,
                      records_received, records_accepted, records_rejected, records_inserted,
                      records_updated, records_unchanged, occupancy_inserted)
                    SELECT :id, id, :corr, now(), now(), 'SUCCESS',
                           1, 1, 0, 0, 0, 0, 1
                    FROM municipal_data_sources WHERE source_key = :sourceKey
                    """)
                    .param("id", syncRunId)
                    .param("corr", "occ-batch-" + i)
                    .param("sourceKey", sourceKey)
                    .update();
            jdbc.sql("""
                    INSERT INTO municipal_occupancy_snapshots(
                      id, facility_id, source_id, source_link_id, sync_run_id, source_observed_at, fetched_at,
                      timestamp_provenance, capacity_total, occupied_spaces, available_spaces,
                      occupancy_status, raw_record_hash, created_at)
                    SELECT :id, :facility, ds.id, lx.id, :runId, now(), now(),
                           'SOURCE', 100, :occ, :avail, 'LIVE', :hash, now()
                    FROM municipal_data_sources ds
                    JOIN municipal_facility_source_links lx ON lx.source_id = ds.id AND lx.facility_id = :facility
                    WHERE ds.source_key = :sourceKey
                    LIMIT 1
                    """)
                    .param("id", UUID.randomUUID())
                    .param("facility", facilityId)
                    .param("runId", syncRunId)
                    .param("occ", 10 + i)
                    .param("avail", 90 - i)
                    .param("hash", "h-" + i)
                    .param("sourceKey", sourceKey)
                    .update();
        }

        SINGLE_FACILITY_CALLS.set(0);
        BATCH_FACILITY_CALLS.set(0);
        BATCH_SOURCE_CALLS.set(0);

        List<MunicipalFacilityQueryService.FacilityView> views = query.nearby(38.40, 27.10, 50_000, n);

        assertThat(views).hasSize(n);
        assertThat(BATCH_FACILITY_CALLS.get())
                .as("list path must use the batch latestForFacilities API")
                .isEqualTo(1);
        assertThat(SINGLE_FACILITY_CALLS.get())
                .as("list path must not fall back to per-facility occupancy lookups")
                .isZero();
        assertThat(BATCH_FACILITY_CALLS.get() + BATCH_SOURCE_CALLS.get())
                .as("occupancy query count stays bounded independent of facility count")
                .isLessThanOrEqualTo(3);

        long izumLive = views.stream()
                .filter(v -> v.freshness() == MunicipalOccupancyFreshness.LIVE
                        || v.freshness() == MunicipalOccupancyFreshness.AGING)
                .count();
        assertThat(izumLive).as("IZUM facilities expose live/aging occupancy").isGreaterThanOrEqualTo(n / 2);
        // OSM has no live-occupancy authority in the catalog: mixed-source list stays discoverable
        // with UNAVAILABLE occupancy (publication preserved, not dropped).
        assertThat(views).allSatisfy(v -> assertThat(v.capacityTotal()).isEqualTo(100));
        assertThat(views.stream().map(MunicipalFacilityQueryService.FacilityView::id).distinct().count())
                .isEqualTo(n);
    }

    private void insertFacility(UUID id, String name, double lat, double lng, int capacity) {
        jdbc.sql("""
                INSERT INTO municipal_parking_facilities(
                  id,operator_name,facility_type,access_classification,display_name,address_text,
                  latitude,longitude,location,capacity_total,active,lifecycle_state,created_at,updated_at)
                VALUES (:id,'Op','OFF_STREET','PUBLIC',:name,'Addr',
                  :lat,:lng,ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography,:capacity,true,'ACTIVE',now(),now())
                """)
                .param("id", id)
                .param("name", name)
                .param("lat", lat)
                .param("lng", lng)
                .param("capacity", capacity)
                .update();
    }

    private void insertLink(UUID facilityId, String sourceKey, String externalId) {
        jdbc.sql("""
                INSERT INTO municipal_facility_source_links(
                  id,facility_id,source_id,external_id,source_name,source_metadata_json,raw_record_hash,
                  first_seen_at,last_seen_at,last_successful_sync_at,active,created_at,updated_at)
                SELECT :id,:facility,id,:external,:name,'{}',:hash,
                  now(),now(),now(),true,now(),now()
                FROM municipal_data_sources WHERE source_key=:sourceKey
                """)
                .param("id", UUID.randomUUID())
                .param("facility", facilityId)
                .param("external", externalId)
                .param("name", "facility")
                .param("hash", externalId + "-v1")
                .param("sourceKey", sourceKey)
                .update();
    }
}
