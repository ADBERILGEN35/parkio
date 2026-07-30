package com.parkio.parking.application;

import com.parkio.parking.application.port.RegistryPersistencePort;
import com.parkio.parking.externalsource.registry.FieldProvenanceSelection;
import com.parkio.parking.infrastructure.metrics.RegistryMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FieldProvenanceApplicationService {
    private final RegistryPersistencePort persistence;
    private final RegistryMetrics metrics;

    public FieldProvenanceApplicationService(
            RegistryPersistencePort persistence, RegistryMetrics metrics) {
        this.persistence = persistence;
        this.metrics = metrics;
    }

    @Transactional
    public void select(FieldProvenanceSelection selection) {
        persistence.upsertProvenance(selection);
        metrics.provenance(selection.field(), "selected");
    }
}
