package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.registry.PublicProvenancePublicationPolicy;
import com.parkio.parking.infrastructure.config.IzelmanProperties;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.ProvenancePublicationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class RegistryPublicationServiceTest {
    private static final UUID FACILITY = UUID.fromString("00000000-0000-0000-0000-000000009001");

    private RegistryProperties properties;
    private MunicipalSourceProperties municipal;
    private IzelmanProperties izelman;
    private SimpleMeterRegistry meterRegistry;
    private ProvenancePublicationMetrics metrics;

    @BeforeEach
    void setUp() {
        properties = new RegistryProperties();
        municipal = new MunicipalSourceProperties();
        municipal.getOsm().setPublicationEnabled(true);
        izelman = new IzelmanProperties();
        meterRegistry = new SimpleMeterRegistry();
        metrics = new ProvenancePublicationMetrics(meterRegistry);
    }

    @Test
    void flagOffReturnsHiddenWithoutQuerying() {
        properties.setProvenancePublicationEnabled(false);
        JdbcClient jdbc = mock(JdbcClient.class);

        RegistryPublicationService service =
                new RegistryPublicationService(properties, jdbc, municipal, izelman, metrics);

        RegistryPublicationService.Enrichment enrichment = service.forFacility(FACILITY);

        assertThat(enrichment.contributingSourceKeys()).isNull();
        assertThat(enrichment.selectedFieldProvenanceSummary()).isNull();
        assertThat(enrichment.registryConfidenceOrReviewStatus()).isNull();
        verify(jdbc, never()).sql(anyString());
    }

    @Test
    void flagOnProjectsBoundedSourceKeysOnly() {
        properties.setProvenancePublicationEnabled(true);
        JdbcClient jdbc = mockJdbcReturning(List.of(
                new PublicProvenancePublicationPolicy.FieldSource("NAME", MunicipalSourceIdentity.IZUM),
                new PublicProvenancePublicationPolicy.FieldSource(
                        "COORDINATES", MunicipalSourceIdentity.OSM),
                new PublicProvenancePublicationPolicy.FieldSource(
                        "TARIFF_ASSIGNMENT", "izelman-open-parking-facilities")));

        RegistryPublicationService service =
                new RegistryPublicationService(properties, jdbc, municipal, izelman, metrics);

        RegistryPublicationService.Enrichment enrichment = service.forFacility(FACILITY);

        assertThat(enrichment.selectedFieldProvenanceSummary())
                .containsEntry("NAME", MunicipalSourceIdentity.IZUM)
                .containsEntry("COORDINATES", MunicipalSourceIdentity.OSM)
                .doesNotContainKey("TARIFF_ASSIGNMENT");
        assertThat(enrichment.contributingSourceKeys())
                .containsExactly(MunicipalSourceIdentity.IZUM, MunicipalSourceIdentity.OSM);
        assertThat(enrichment.registryConfidenceOrReviewStatus()).isNull();
        assertThat(enrichment.selectedFieldProvenanceSummary().values())
                .noneMatch(v -> v.contains(":") || v.contains("REVIEW") || v.contains("CURRENT"));
    }

    @Test
    void flagOnWithNoRowsReturnsEmptyNotNullMaps() {
        properties.setProvenancePublicationEnabled(true);
        JdbcClient jdbc = mockJdbcReturning(List.of());

        RegistryPublicationService service =
                new RegistryPublicationService(properties, jdbc, municipal, izelman, metrics);

        RegistryPublicationService.Enrichment enrichment = service.forFacility(FACILITY);

        assertThat(enrichment.contributingSourceKeys()).isEmpty();
        assertThat(enrichment.selectedFieldProvenanceSummary()).isEmpty();
        assertThat(enrichment.registryConfidenceOrReviewStatus()).isNull();
    }

    @Test
    void killSwitchAfterEnrichmentRestoresHiddenWithoutFurtherQueries() {
        properties.setProvenancePublicationEnabled(true);
        JdbcClient jdbc = mockJdbcReturning(List.of(
                new PublicProvenancePublicationPolicy.FieldSource("NAME", MunicipalSourceIdentity.IZUM)));
        RegistryPublicationService service =
                new RegistryPublicationService(properties, jdbc, municipal, izelman, metrics);

        assertThat(service.forFacility(FACILITY).selectedFieldProvenanceSummary()).isNotEmpty();

        properties.setProvenancePublicationEnabled(false);
        RegistryPublicationService.Enrichment hidden = service.forFacility(FACILITY);
        assertThat(hidden.contributingSourceKeys()).isNull();
        assertThat(hidden.selectedFieldProvenanceSummary()).isNull();
        assertThat(meterRegistry.find("parkio.municipal.registry.provenance.publication")
                        .tag("outcome", "hidden")
                        .counter())
                .isNotNull();
        assertThat(meterRegistry.find("parkio.municipal.registry.provenance.publication")
                        .tag("outcome", "enriched")
                        .tag("source_family", "izum")
                        .tag("policy_version", PublicProvenancePublicationPolicy.POLICY_VERSION)
                        .counter())
                .isNotNull();
        assertThat(meterRegistry.find("parkio.municipal.registry.provenance.publication.fields")
                        .tag("field_name", "NAME")
                        .tag("policy_version", PublicProvenancePublicationPolicy.POLICY_VERSION)
                        .counter())
                .isNotNull();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JdbcClient mockJdbcReturning(
            List<PublicProvenancePublicationPolicy.FieldSource> rows) {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec mapped = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(mapped);
        when(mapped.list()).thenReturn(rows);
        return jdbc;
    }
}