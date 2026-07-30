package com.parkio.parking.application;

import com.parkio.parking.externalsource.MunicipalSyncRunStatus;

public record OsmImportResult(
        MunicipalSyncRunStatus status,
        boolean dryRun,
        String inputFilename,
        String sha256,
        String clipVersion,
        int elementsRead,
        int extracted,
        int rejected,
        int inserted,
        int updated,
        int unchanged,
        int deactivated,
        int reactivated,
        int conflationCandidates,
        int autoMatched,
        int reviewRequired,
        int rejectedMatches,
        int hardConflicts,
        String errorCategory,
        String errorSummary,
        String qualityReportJson) {}