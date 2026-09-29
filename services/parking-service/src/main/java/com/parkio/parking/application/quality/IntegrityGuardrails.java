package com.parkio.parking.application.quality;

/**
 * Invariant counters an operator can read at a glance. Duplicate counters are expected
 * to stay at zero because unique constraints enforce them; they are queried anyway so a
 * schema regression is visible rather than assumed.
 */
public record IntegrityGuardrails(
        long duplicateSourceLinkGroups,
        long duplicateProvenanceGroups,
        long linkCandidates,
        long pendingLinkCandidates,
        long linkReviewDecisions,
        long facilityAliases,
        long tariffPlans,
        long activeTariffAssignments,
        long izelmanLinkedActiveFacilities,
        long osmOccupancySnapshots) {}
