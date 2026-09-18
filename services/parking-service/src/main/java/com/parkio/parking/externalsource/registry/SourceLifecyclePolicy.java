package com.parkio.parking.externalsource.registry;

public final class SourceLifecyclePolicy {
    private SourceLifecyclePolicy() {}

    public enum FacilityLifecycle {
        ACTIVE,
        SUPERSEDED,
        UNPUBLISHED_ONLY,
        NO_ACTIVE_SOURCE_LINKS
    }

    public enum SourceEvent {
        COMPLETE_SUCCESS_PRESENT,
        COMPLETE_SUCCESS_DISAPPEARED,
        PARTIAL_SUCCESS,
        FAILURE,
        REACTIVATED
    }

    public record Decision(
            boolean deactivateSourceLink,
            boolean reactivateSourceLink,
            boolean retainCanonicalFacility,
            boolean publishFacility,
            boolean retainAvailabilityHistory,
            boolean allowNewAvailability) {}

    public static Decision decide(
            SourceEvent event,
            boolean hasOtherActiveLinks,
            boolean anyPublishableActiveLink,
            boolean izumLinkActive) {
        return switch (event) {
            case COMPLETE_SUCCESS_DISAPPEARED -> new Decision(
                    true, false, true, hasOtherActiveLinks && anyPublishableActiveLink, true, izumLinkActive);
            case REACTIVATED, COMPLETE_SUCCESS_PRESENT -> new Decision(
                    false, event == SourceEvent.REACTIVATED, true, anyPublishableActiveLink, true, izumLinkActive);
            case PARTIAL_SUCCESS, FAILURE -> new Decision(
                    false, false, true, anyPublishableActiveLink, true, izumLinkActive);
        };
    }

    public static boolean staleAvailabilityMayRemainHistory() {
        return true;
    }

    public static boolean staleAvailabilityMayBeReportedLive() {
        return false;
    }
}
