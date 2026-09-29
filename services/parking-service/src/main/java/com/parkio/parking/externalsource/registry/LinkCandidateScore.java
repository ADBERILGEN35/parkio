package com.parkio.parking.externalsource.registry;

import java.util.Map;
import java.util.Set;

public record LinkCandidateScore(
        boolean candidate,
        double total,
        Map<String, Double> components,
        Set<String> hardConflicts,
        Set<String> supportingSignals,
        String reasonCategory) {

    public LinkCandidateScore {
        components = Map.copyOf(components);
        hardConflicts = Set.copyOf(hardConflicts);
        supportingSignals = Set.copyOf(supportingSignals);
        if (!Double.isFinite(total) || total < 0 || total > 1) {
            throw new IllegalArgumentException("total must be between zero and one");
        }
    }

    public boolean reviewRequired() {
        return candidate || !hardConflicts.isEmpty();
    }

    public boolean mayAutoLink() {
        return false;
    }
}
