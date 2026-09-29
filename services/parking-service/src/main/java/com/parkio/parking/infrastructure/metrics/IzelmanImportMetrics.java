package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.IzelmanImportResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class IzelmanImportMetrics {
    private final MeterRegistry registry;

    public IzelmanImportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(IzelmanImportResult result) {
        registry.counter("parkio.municipal.izelman.imports",
                "source_key", result.sourceKey(),
                "status", result.status().name(),
                "outcome", result.errorCategory() == null ? "success" : result.errorCategory(),
                "data_type", result.dataType(),
                "age_classification", result.ageClassification().name()).increment();
    }
}
