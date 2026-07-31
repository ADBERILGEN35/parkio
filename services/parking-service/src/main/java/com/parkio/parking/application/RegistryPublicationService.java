package com.parkio.parking.application;

import com.parkio.parking.externalsource.MunicipalSourcePublicationPolicy;
import com.parkio.parking.externalsource.registry.PublicProvenancePublicationPolicy;
import com.parkio.parking.externalsource.registry.PublicProvenancePublicationPolicy.BoundedProvenance;
import com.parkio.parking.externalsource.registry.PublicProvenancePublicationPolicy.FieldSource;
import com.parkio.parking.infrastructure.config.IzelmanProperties;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.config.RegistryProperties;
import com.parkio.parking.infrastructure.metrics.ProvenancePublicationMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DATA-WP-09 read-only public provenance enrichment for nearby/detail DTOs.
 * Flag default false. Never mutates registry, links, reviews, candidates,
 * occupancy, or tariffs. Never publishes confidence, review status, or IDs.
 */
@Service
public class RegistryPublicationService {
    /**
     * Public DTO enrichment. {@code registryConfidenceOrReviewStatus} is retained for
     * backward-compatible JSON shape but is always null (never published).
     */
    public record Enrichment(
            List<String> contributingSourceKeys,
            Map<String, String> selectedFieldProvenanceSummary,
            String registryConfidenceOrReviewStatus) {
        public static Enrichment hidden() {
            return new Enrichment(null, null, null);
        }

        public static Enrichment of(BoundedProvenance provenance) {
            return new Enrichment(
                    provenance.contributingSourceKeys(),
                    provenance.selectedFieldProvenanceSummary(),
                    null);
        }
    }

    private final RegistryProperties properties;
    private final JdbcClient jdbc;
    private final PublicProvenancePublicationPolicy policy;
    private final ProvenancePublicationMetrics metrics;

    public RegistryPublicationService(
            RegistryProperties properties,
            JdbcClient jdbc,
            MunicipalSourceProperties municipalSourceProperties,
            IzelmanProperties izelmanProperties,
            ProvenancePublicationMetrics metrics) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.policy = new PublicProvenancePublicationPolicy(
                new MunicipalSourcePublicationPolicy(municipalSourceProperties, izelmanProperties));
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public Enrichment forFacility(UUID facilityId) {
        if (!properties.isProvenancePublicationEnabled()) {
            metrics.recordHidden();
            return Enrichment.hidden();
        }
        List<String> allowlist = List.copyOf(PublicProvenancePublicationPolicy.PUBLIC_FIELD_ALLOWLIST);
        List<FieldSource> rows = new ArrayList<>();
        jdbc.sql("""
                SELECT field_name, source_key
                FROM municipal_facility_field_provenance
                WHERE facility_id = :facility
                  AND field_name IN (:fields)
                ORDER BY field_name
                """)
                .param("facility", facilityId)
                .param("fields", allowlist)
                .query((rs, rowNum) -> new FieldSource(
                        rs.getString("field_name"),
                        rs.getString("source_key")))
                .list()
                .forEach(rows::add);

        BoundedProvenance projected = policy.project(rows);
        if (projected.isEmpty()) {
            metrics.recordEmpty();
            return Enrichment.of(projected);
        }
        metrics.recordEnriched(projected);
        return Enrichment.of(projected);
    }
}
