package com.parkio.parking.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.testsupport.PostgisTestImages;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Regression: production roadside nearby failed with
 * {@code conversion to class java.time.Instant from timestamptz not supported}
 * when mapping {@code updated_at} via {@code ResultSet#getObject(..., Instant.class)}.
 * Mapping must use {@code getTimestamp(...).toInstant()}.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class RoadsideParkingControllerTimestamptzIT {
    private static final DockerImageName POSTGIS = PostgisTestImages.dockerImageName();
    private static final UUID SEGMENT_ID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeee0001");
    private static final UUID LINK_ID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeee0002");
    private static final Instant UPDATED_AT = Instant.parse("2022-11-25T12:34:56Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS)
            .withDatabaseName("parkio_roadside_ts_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @Autowired RoadsideParkingController controller;
    @Autowired JdbcTemplate jdbc;

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
        registry.add("parkio.municipal.izelman.enabled", () -> "true");
        registry.add("parkio.municipal.izelman.roadside-publication-enabled", () -> "true");
    }

    @BeforeEach
    void seedPublishedRoadsideWithTimestamptz() {
        jdbc.update("DELETE FROM municipal_roadside_source_links");
        jdbc.update("DELETE FROM municipal_roadside_segments");
        UUID sourceId = jdbc.queryForObject(
                "SELECT id FROM municipal_data_sources WHERE source_key=?",
                UUID.class,
                IzelmanSourceKeys.ROADSIDE);
        assertThat(sourceId).isNotNull();
        java.sql.Timestamp ts = java.sql.Timestamp.from(UPDATED_AT);
        jdbc.update("""
                INSERT INTO municipal_roadside_segments(
                  id, display_name, district, neighborhood, address_or_description,
                  latitude, longitude, capacity_total, geometry_kind, source_age_classification,
                  publication_status, active, created_at, updated_at)
                VALUES (?, 'LİMAN -3', 'Konak', 'Alsancak', 'test roadside',
                  38.438, 27.142, 10, 'POINT', 'HISTORICAL',
                  'PUBLISHED', true, ?, ?)
                """,
                SEGMENT_ID, ts, ts);
        jdbc.update("""
                INSERT INTO municipal_roadside_source_links(
                  id, segment_id, source_id, external_id, raw_record_hash,
                  first_seen_at, last_seen_at, active, created_at, updated_at)
                VALUES (?, ?, ?, 'ext-liman-3', 'hash-liman-3',
                  ?, ?, true, ?, ?)
                """,
                LINK_ID, SEGMENT_ID, sourceId, ts, ts, ts, ts);
    }

    @Test
    void nearbyMapsTimestamptzUpdatedAtWithoutIntegrityFailure() {
        assertThatCode(() -> controller.nearby(38.438, 27.142, 5000, 20))
                .doesNotThrowAnyException();
        List<RoadsideParkingController.RoadsideView> views = controller.nearby(38.438, 27.142, 5000, 20);
        assertThat(views).hasSize(1);
        RoadsideParkingController.RoadsideView view = views.getFirst();
        assertThat(view.id()).isEqualTo(SEGMENT_ID);
        assertThat(view.displayName()).isEqualTo("LİMAN -3");
        assertThat(view.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(view.availableSpaces()).isNull();
        assertThat(view.sourceAgeClassification()).isEqualTo("HISTORICAL");
    }
}
