package com.parkio.parking.infrastructure.persistence.municipal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.parkio.parking.testsupport.PostgisTestImages;

@Tag("integration")
@Testcontainers
class MunicipalRegistryMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            PostgisTestImages.dockerImageName());

    @Test
    void migratesFromV31ToV32WithoutRemovingExistingLinks() throws Exception {
        Flyway throughV31 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("31")
                .cleanDisabled(false)
                .load();
        throughV31.clean();
        throughV31.migrate();

        Flyway current = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        current.migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            for (String table : new String[] {
                "municipal_facility_field_provenance",
                "municipal_link_candidates",
                "municipal_link_review_audit",
                "municipal_facility_aliases"
            }) {
                assertThat(count(connection,
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE table_schema='public' AND table_name='" + table + "'"))
                        .isEqualTo(1);
            }
            assertThat(count(connection, """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name='municipal_parking_facilities'
                      AND column_name IN ('lifecycle_state','superseded_by_id')
                    """)).isEqualTo(2);
            assertThat(count(connection, """
                    SELECT count(*) FROM information_schema.triggers
                    WHERE event_object_table='municipal_link_review_audit'
                      AND trigger_name='trg_municipal_link_review_audit_immutable'
                    """)).isEqualTo(2);

            String candidateInsert = """
                    INSERT INTO municipal_link_candidates (
                        id,source_key_a,external_id_a,source_key_b,external_id_b,source_family_pair,
                        evidence_signals_json,score_components_json,total_score,hard_conflicts,
                        generated_at,source_version_a,source_version_b,review_state,algorithm_version,
                        version,created_at,updated_at)
                    VALUES (
                        %s,'izmir-izum-otoparklar','a','osm-geofabrik-turkey','b','IZUM_OSM',
                        '{}'::jsonb,'{}'::jsonb,0.7,'[]'::jsonb,now(),'v1',%s,'PENDING',
                        'registry-link-candidate-v1',0,now(),now())
                    ON CONFLICT (
                        source_key_a,external_id_a,source_key_b,external_id_b,
                        source_version_a,source_version_b,algorithm_version) DO NOTHING
                    """;
            execute(connection, candidateInsert.formatted(
                    "'00000000-0000-0000-0000-000000000041'", "'v1'"));
            execute(connection, """
                    UPDATE municipal_link_candidates
                    SET review_state='REJECTED',version=1,rejection_reason='insufficient evidence'
                    WHERE id='00000000-0000-0000-0000-000000000041'
                    """);
            execute(connection, candidateInsert.formatted(
                    "'00000000-0000-0000-0000-000000000042'", "'v1'"));
            assertThat(count(connection, "SELECT count(*) FROM municipal_link_candidates"))
                    .isEqualTo(1);
            execute(connection, candidateInsert.formatted(
                    "'00000000-0000-0000-0000-000000000043'", "'v2'"));
            assertThat(count(connection, "SELECT count(*) FROM municipal_link_candidates"))
                    .isEqualTo(2);
        }
    }

    @Test
    void auditRowsAreImmutable() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(connection, """
                    INSERT INTO municipal_link_candidates (
                        id,source_key_a,external_id_a,source_key_b,external_id_b,source_family_pair,
                        evidence_signals_json,score_components_json,total_score,hard_conflicts,
                        generated_at,source_version_a,source_version_b,review_state,algorithm_version,
                        version,created_at,updated_at)
                    VALUES (
                        '00000000-0000-0000-0000-000000000051','izmir-izum-otoparklar','a',
                        'osm-geofabrik-turkey','b','IZUM_OSM','{}'::jsonb,'{}'::jsonb,0.7,'[]'::jsonb,
                        now(),'v1','v1','PENDING','registry-link-candidate-v1',0,now(),now())
                    """);
            execute(connection, """
                    INSERT INTO municipal_link_review_audit (
                        id,candidate_id,previous_state,new_state,reviewer,decision_reason,
                        chosen_facility_id,candidate_version,decision_ts)
                    VALUES (
                        '00000000-0000-0000-0000-000000000061',
                        '00000000-0000-0000-0000-000000000051',
                        'PENDING','REJECTED','admin','insufficient',null,0,now())
                    """);
            boolean updateFailed = false;
            try {
                execute(connection, """
                        UPDATE municipal_link_review_audit
                        SET reviewer='mutated'
                        WHERE id='00000000-0000-0000-0000-000000000061'
                        """);
            } catch (Exception ex) {
                updateFailed = true;
            }
            assertThat(updateFailed).isTrue();
        }
    }

    private static long count(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void execute(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}