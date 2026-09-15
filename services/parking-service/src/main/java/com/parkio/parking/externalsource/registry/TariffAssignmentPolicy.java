package com.parkio.parking.externalsource.registry;

import java.util.Set;

public final class TariffAssignmentPolicy {
    private static final Set<String> STRONG_SIGNALS =
            Set.of("official_facility_id", "official_tariff_code", "explicit_source_assignment");

    private TariffAssignmentPolicy() {}

    public record Evidence(
            boolean explicitAssignment,
            Set<String> signals,
            FieldProvenanceSelection.SourceAgeClass ageClass) {
        public Evidence {
            signals = signals == null ? Set.of() : Set.copyOf(signals);
        }
    }

    public static boolean mayAssign(Evidence evidence) {
        if (evidence == null
                || !evidence.explicitAssignment()
                || evidence.ageClass() != FieldProvenanceSelection.SourceAgeClass.CURRENT) {
            return false;
        }
        return evidence.signals().stream().anyMatch(STRONG_SIGNALS::contains);
    }

    public static boolean proximityOnlyIsSufficient() {
        return false;
    }
}
