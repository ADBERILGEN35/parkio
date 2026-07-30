package com.parkio.parking.externalsource;

public record MunicipalSyncResult(
        MunicipalSyncRunStatus status,
        int recordsReceived,
        int recordsAccepted,
        int recordsRejected,
        int recordsInserted,
        int recordsUpdated,
        int recordsUnchanged,
        int occupancyInserted,
        String errorCategory,
        String errorSummary) {}
