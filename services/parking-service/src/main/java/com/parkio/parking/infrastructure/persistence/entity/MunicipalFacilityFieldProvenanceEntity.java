package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "municipal_facility_field_provenance")
public class MunicipalFacilityFieldProvenanceEntity {
    @Id private UUID id;
    @Column(name = "facility_id", nullable = false) private UUID facilityId;
    @Column(name = "field_name", nullable = false) private String fieldName;
    @Column(name = "source_key", nullable = false) private String sourceKey;
    @Column(name = "source_record_id", nullable = false) private String sourceRecordId;
    @Column(name = "source_content_ts") private Instant sourceContentTs;
    @Column(name = "fetch_ts", nullable = false) private Instant fetchTs;
    @Column(name = "source_age_class", nullable = false) private String sourceAgeClass;
    @Column(name = "confidence_or_review_state", nullable = false) private String confidenceOrReviewState;
    @Column(name = "selection_reason", nullable = false) private String selectionReason;
    @Column(name = "last_selected_at", nullable = false) private Instant lastSelectedAt;
    @Version private long version;

    protected MunicipalFacilityFieldProvenanceEntity() {}
}
