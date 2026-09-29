package com.parkio.parking.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "municipal_link_review_audit")
public class MunicipalLinkReviewAuditEntity {
    @Id private UUID id;
    @Column(name = "candidate_id", nullable = false) private UUID candidateId;
    @Column(name = "previous_state", nullable = false) private String previousState;
    @Column(name = "new_state", nullable = false) private String newState;
    @Column(nullable = false) private String reviewer;
    @Column(name = "decision_reason") private String decisionReason;
    @Column(name = "chosen_facility_id") private UUID chosenFacilityId;
    @Column(name = "candidate_version", nullable = false) private long candidateVersion;
    @Column(name = "decision_ts", nullable = false) private Instant decisionTs;

    protected MunicipalLinkReviewAuditEntity() {}

    public MunicipalLinkReviewAuditEntity(
            UUID id,
            UUID candidateId,
            String previousState,
            String newState,
            String reviewer,
            String decisionReason,
            UUID chosenFacilityId,
            long candidateVersion,
            Instant decisionTs) {
        this.id = id;
        this.candidateId = candidateId;
        this.previousState = previousState;
        this.newState = newState;
        this.reviewer = reviewer;
        this.decisionReason = decisionReason;
        this.chosenFacilityId = chosenFacilityId;
        this.candidateVersion = candidateVersion;
        this.decisionTs = decisionTs;
    }
}
