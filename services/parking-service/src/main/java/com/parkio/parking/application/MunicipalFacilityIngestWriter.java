package com.parkio.parking.application;

import com.parkio.parking.application.port.MunicipalFacilityRepository;
import com.parkio.parking.application.port.MunicipalOccupancySnapshotRepository;
import com.parkio.parking.application.port.MunicipalSourceLinkRepository;
import com.parkio.parking.externalsource.NormalizedMunicipalFacility;
import com.parkio.parking.externalsource.NormalizedMunicipalOccupancy;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-facility municipal persist units (DATA-WP-10): facility + link (+ occupancy) + provenance
 * commit together so a mid-facility failure cannot leave orphan provenance.
 */
@Service
public class MunicipalFacilityIngestWriter {
    private final MunicipalFacilityRepository facilities;
    private final MunicipalSourceLinkRepository links;
    private final MunicipalOccupancySnapshotRepository snapshots;
    private final FieldProvenanceApplicationService provenance;

    public MunicipalFacilityIngestWriter(
            MunicipalFacilityRepository facilities,
            MunicipalSourceLinkRepository links,
            MunicipalOccupancySnapshotRepository snapshots,
            FieldProvenanceApplicationService provenance) {
        this.facilities = facilities;
        this.links = links;
        this.snapshots = snapshots;
        this.provenance = provenance;
    }

    public record FacilityPersistResult(
            UUID facilityId, boolean inserted, boolean changed, boolean occupancyInserted) {}

    @Transactional
    public FacilityPersistResult persistLiveAdapterFacility(
            UUID sourceId,
            UUID syncRunId,
            String sourceKey,
            NormalizedMunicipalFacility facility,
            NormalizedMunicipalOccupancy occupancy,
            Instant fetchedAt) {
        var upserted = facilities.upsert(sourceId, facility, fetchedAt);
        UUID linkId = links.upsert(upserted.id(), sourceId, facility, fetchedAt);
        boolean occupancyInserted = false;
        if (occupancy != null
                && snapshots.insertIfAbsent(upserted.id(), sourceId, linkId, syncRunId, occupancy)) {
            occupancyInserted = true;
        }
        provenance.applyLiveAdapterIngest(upserted.id(), sourceKey, facility, fetchedAt);
        return new FacilityPersistResult(
                upserted.id(), upserted.inserted(), upserted.changed(), occupancyInserted);
    }

    /** Compatibility alias for live İZUM ingest. */
    @Deprecated
    @Transactional
    public FacilityPersistResult persistIzumFacility(
            UUID sourceId,
            UUID syncRunId,
            NormalizedMunicipalFacility facility,
            NormalizedMunicipalOccupancy occupancy,
            Instant fetchedAt) {
        return persistLiveAdapterFacility(
                sourceId,
                syncRunId,
                com.parkio.parking.externalsource.MunicipalSourceIdentity.IZUM,
                facility,
                occupancy,
                fetchedAt);
    }

    /**
     * Per-facility OSM persist unit. REQUIRES_NEW isolates from the import-loop transaction so a
     * swallowed mid-import failure cannot commit a facility without its provenance selections.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FacilityPersistResult persistOsmFacility(
            UUID sourceId,
            NormalizedMunicipalFacility facility,
            boolean osmNameTagPresent,
            Instant fetchedAt) {
        var upserted = facilities.upsert(sourceId, facility, fetchedAt);
        links.upsert(upserted.id(), sourceId, facility, fetchedAt);
        provenance.applyOsmIngest(upserted.id(), facility, osmNameTagPresent, fetchedAt);
        return new FacilityPersistResult(
                upserted.id(), upserted.inserted(), upserted.changed(), false);
    }
}