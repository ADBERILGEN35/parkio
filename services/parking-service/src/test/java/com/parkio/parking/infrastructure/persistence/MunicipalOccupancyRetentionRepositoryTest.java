package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MunicipalOccupancyRetentionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(7));

    private MunicipalOccupancySnapshotRepositoryAdapter repo;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:occ_retention;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbc = JdbcClient.create(ds);
        repo = new MunicipalOccupancySnapshotRepositoryAdapter(jdbc);
        jdbc.sql("DROP TABLE IF EXISTS municipal_occupancy_snapshots").update();
        jdbc.sql("""
                CREATE TABLE municipal_occupancy_snapshots (
                    id UUID PRIMARY KEY,
                    facility_id UUID NOT NULL,
                    source_id UUID NOT NULL,
                    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """).update();
    }

    @Test
    void deletesOldNonLatestAndPreservesLatestEvenWhenOld() {
        UUID facility = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        // All older than 7d; latest is still 30d old (provider outage case).
        UUID nonLatestOlder = insert(facility, source, NOW.minus(Duration.ofDays(50)));
        UUID nonLatestMid = insert(facility, source, NOW.minus(Duration.ofDays(40)));
        UUID latestOld = insert(facility, source, NOW.minus(Duration.ofDays(30)));

        assertThat(repo.countExpiredExcludingLatest(CUTOFF)).isEqualTo(2);
        assertThat(repo.deleteExpiredExcludingLatest(CUTOFF, 100)).isEqualTo(2);
        Set<UUID> remaining = ids();
        assertThat(remaining).containsExactly(latestOld);
        assertThat(remaining).doesNotContain(nonLatestOlder, nonLatestMid);
        assertThat(repo.countExpiredExcludingLatest(CUTOFF)).isZero();
        assertThat(repo.deleteExpiredExcludingLatest(CUTOFF, 100)).isZero();
    }

    @Test
    void recentSnapshotsPreserved() {
        UUID facility = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID a = insert(facility, source, NOW.minus(Duration.ofDays(2)));
        UUID b = insert(facility, source, NOW.minus(Duration.ofDays(1)));
        assertThat(repo.countExpiredExcludingLatest(CUTOFF)).isZero();
        assertThat(repo.deleteExpiredExcludingLatest(CUTOFF, 10)).isZero();
        assertThat(ids()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void isolatesProvidersAndFacilities() {
        UUID f1 = UUID.randomUUID();
        UUID f2 = UUID.randomUUID();
        UUID izum = UUID.randomUUID();
        UUID ispark = UUID.randomUUID();
        UUID f1IzumLatest = insert(f1, izum, NOW.minus(Duration.ofDays(40)));
        UUID f1IzumOld = insert(f1, izum, NOW.minus(Duration.ofDays(41)));
        UUID f1IsparkLatest = insert(f1, ispark, NOW.minus(Duration.ofDays(40)));
        UUID f1IsparkOld = insert(f1, ispark, NOW.minus(Duration.ofDays(50)));
        UUID f2IzumLatest = insert(f2, izum, NOW.minus(Duration.ofDays(40)));
        UUID f2IzumOld = insert(f2, izum, NOW.minus(Duration.ofDays(45)));

        assertThat(repo.deleteExpiredExcludingLatest(CUTOFF, 100)).isEqualTo(3);
        assertThat(ids()).containsExactlyInAnyOrder(f1IzumLatest, f1IsparkLatest, f2IzumLatest);
        assertThat(ids()).doesNotContain(f1IzumOld, f1IsparkOld, f2IzumOld);
    }

    @Test
    void sameTimestampTieUsesIdDescAsLatest() {
        UUID facility = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        Instant ts = NOW.minus(Duration.ofDays(10));
        // Insert lower id first so higher id wins as latest under ORDER BY fetched_at DESC, id DESC
        UUID lower = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higher = UUID.fromString("00000000-0000-0000-0000-000000000002");
        insertAt(lower, facility, source, ts);
        insertAt(higher, facility, source, ts);

        assertThat(repo.deleteExpiredExcludingLatest(CUTOFF, 10)).isEqualTo(1);
        assertThat(ids()).containsExactly(higher);
    }

    @Test
    void oneSnapshotOnlyFacilitySafe() {
        UUID id = insert(UUID.randomUUID(), UUID.randomUUID(), NOW.minus(Duration.ofDays(60)));
        assertThat(repo.countExpiredExcludingLatest(CUTOFF)).isZero();
        assertThat(repo.deleteExpiredExcludingLatest(CUTOFF, 10)).isZero();
        assertThat(ids()).containsExactly(id);
    }

    @Test
    void emptyTableSafe() {
        assertThat(repo.count()).isZero();
        assertThat(repo.countExpiredExcludingLatest(CUTOFF)).isZero();
        assertThat(repo.deleteExpiredExcludingLatest(CUTOFF, 10)).isZero();
    }

    @Test
    void batchLimitHonored() {
        UUID facility = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        insert(facility, source, NOW.minus(Duration.ofDays(40))); // latest protected
        insert(facility, source, NOW.minus(Duration.ofDays(30)));
        insert(facility, source, NOW.minus(Duration.ofDays(20)));
        insert(facility, source, NOW.minus(Duration.ofDays(15)));
        assertThat(repo.deleteExpiredExcludingLatest(CUTOFF, 1)).isEqualTo(1);
        assertThat(repo.countExpiredExcludingLatest(CUTOFF)).isEqualTo(2);
    }

    private UUID insert(UUID facilityId, UUID sourceId, Instant fetchedAt) {
        UUID id = UUID.randomUUID();
        insertAt(id, facilityId, sourceId, fetchedAt);
        return id;
    }

    private void insertAt(UUID id, UUID facilityId, UUID sourceId, Instant fetchedAt) {
        jdbc.sql("""
                INSERT INTO municipal_occupancy_snapshots (id, facility_id, source_id, fetched_at)
                VALUES (:id, :facilityId, :sourceId, :fetchedAt)
                """)
                .param("id", id)
                .param("facilityId", facilityId)
                .param("sourceId", sourceId)
                .param("fetchedAt", Timestamp.from(fetchedAt))
                .update();
    }

    private Set<UUID> ids() {
        List<UUID> list = jdbc.sql("SELECT id FROM municipal_occupancy_snapshots")
                .query(UUID.class)
                .list();
        return new HashSet<>(list);
    }
}
