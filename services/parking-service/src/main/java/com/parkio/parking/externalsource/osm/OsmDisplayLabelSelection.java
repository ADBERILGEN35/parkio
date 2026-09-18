package com.parkio.parking.externalsource.osm;

import java.util.Objects;

/** Result of {@link OsmDisplayLabelPolicy} for one accepted OSM parking feature. */
public record OsmDisplayLabelSelection(
        String displayLabel,
        OsmDisplayLabelOutcome outcome,
        String policyVersion,
        int rejectedCandidateCount,
        int technicalIdRejectedCount) {

    public OsmDisplayLabelSelection {
        Objects.requireNonNull(displayLabel, "displayLabel");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(policyVersion, "policyVersion");
        if (displayLabel.isBlank()) {
            throw new IllegalArgumentException("displayLabel must not be blank");
        }
    }

    public boolean nameBearing() {
        return outcome.nameBearing();
    }
}
