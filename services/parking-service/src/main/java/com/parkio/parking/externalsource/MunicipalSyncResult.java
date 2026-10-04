package com.parkio.parking.externalsource;

/**
 * Outcome of a municipal source sync/import run.
 *
 * <p>{@code recordsDeactivated}, {@code recordsReactivated}, and {@code activeLinkCount} are
 * populated by authoritative set-reconciliation (İZUM). OSM/İZELMAN paths that do not track
 * those counters pass zeros via the compatibility constructor.
 *
 * <p>{@code incompleteSnapshotSkipped} is true when an İZUM feed was smaller than the active set and
 * was not reconciled, so the missing facilities stayed active: the incomplete-snapshot guard skipped
 * it, or invalid rows made it untrustworthy (CL-F22).
 */
public record MunicipalSyncResult(
        MunicipalSyncRunStatus status,
        int recordsReceived,
        int recordsAccepted,
        int recordsRejected,
        int recordsInserted,
        int recordsUpdated,
        int recordsUnchanged,
        int occupancyInserted,
        int recordsDeactivated,
        int recordsReactivated,
        int activeLinkCount,
        String errorCategory,
        String errorSummary,
        boolean incompleteSnapshotSkipped) {

    /** Compatibility constructor for results without an incomplete-snapshot skip. */
    public MunicipalSyncResult(
            MunicipalSyncRunStatus status,
            int recordsReceived,
            int recordsAccepted,
            int recordsRejected,
            int recordsInserted,
            int recordsUpdated,
            int recordsUnchanged,
            int occupancyInserted,
            int recordsDeactivated,
            int recordsReactivated,
            int activeLinkCount,
            String errorCategory,
            String errorSummary) {
        this(
                status,
                recordsReceived,
                recordsAccepted,
                recordsRejected,
                recordsInserted,
                recordsUpdated,
                recordsUnchanged,
                occupancyInserted,
                recordsDeactivated,
                recordsReactivated,
                activeLinkCount,
                errorCategory,
                errorSummary,
                false);
    }

    /** Compatibility constructor when set-reconciliation counters are not applicable. */
    public MunicipalSyncResult(
            MunicipalSyncRunStatus status,
            int recordsReceived,
            int recordsAccepted,
            int recordsRejected,
            int recordsInserted,
            int recordsUpdated,
            int recordsUnchanged,
            int occupancyInserted,
            String errorCategory,
            String errorSummary) {
        this(
                status,
                recordsReceived,
                recordsAccepted,
                recordsRejected,
                recordsInserted,
                recordsUpdated,
                recordsUnchanged,
                occupancyInserted,
                0,
                0,
                0,
                errorCategory,
                errorSummary);
    }
}
