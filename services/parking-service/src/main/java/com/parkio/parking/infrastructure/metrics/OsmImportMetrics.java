package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.OsmImportResult;
import com.parkio.parking.infrastructure.osm.OsmGeofabrikSourceKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Bounded-label OSM import metrics — never facility/OSM IDs, paths, or exception text. */
@Component
public class OsmImportMetrics {
    private final MeterRegistry registry;

    public OsmImportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(OsmImportResult result, Duration duration) {
        String sourceKey = OsmGeofabrikSourceKeys.SOURCE_KEY;
        String status = result.status().name();
        String error = result.errorCategory() == null ? "none" : result.errorCategory();
        registry.counter("parkio.municipal.osm.import.runs",
                        "source_key", sourceKey, "status", status, "error_category", error)
                .increment();
        Timer.builder("parkio.municipal.osm.import.duration")
                .tag("source_key", sourceKey)
                .tag("status", status)
                .register(registry)
                .record(duration == null ? Duration.ZERO : duration);
        registry.counter("parkio.municipal.osm.import.elements", "source_key", sourceKey, "outcome", "read")
                .increment(result.elementsRead());
        registry.counter("parkio.municipal.osm.import.elements", "source_key", sourceKey, "outcome", "extracted")
                .increment(result.extracted());
        registry.counter("parkio.municipal.osm.import.elements", "source_key", sourceKey, "outcome", "rejected")
                .increment(result.rejected());
        registry.counter("parkio.municipal.osm.import.facilities", "source_key", sourceKey, "outcome", "inserted")
                .increment(result.inserted());
        registry.counter("parkio.municipal.osm.import.facilities", "source_key", sourceKey, "outcome", "updated")
                .increment(result.updated());
        registry.counter("parkio.municipal.osm.import.facilities", "source_key", sourceKey, "outcome", "deactivated")
                .increment(result.deactivated());
        registry.counter("parkio.municipal.osm.conflation", "source_key", sourceKey, "decision", "candidate")
                .increment(result.conflationCandidates());
        registry.counter("parkio.municipal.osm.conflation", "source_key", sourceKey, "decision", "AUTO_MATCHED")
                .increment(result.autoMatched());
        registry.counter("parkio.municipal.osm.conflation", "source_key", sourceKey, "decision", "REVIEW_REQUIRED")
                .increment(result.reviewRequired());
        registry.counter("parkio.municipal.osm.conflation", "source_key", sourceKey, "decision", "REJECTED")
                .increment(result.rejectedMatches());
        registry.counter("parkio.municipal.osm.conflation", "source_key", sourceKey, "decision", "HARD_CONFLICT")
                .increment(result.hardConflicts());
    }
}