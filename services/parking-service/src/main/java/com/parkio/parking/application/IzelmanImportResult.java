package com.parkio.parking.application;

import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import com.parkio.parking.externalsource.izelman.SourceAgeClassification;

public record IzelmanImportResult(
        MunicipalSyncRunStatus status,
        String sourceKey,
        String dataType,
        boolean dryRun,
        int recordsRead,
        int accepted,
        int rejected,
        int inserted,
        int updated,
        int unchanged,
        int deactivated,
        SourceAgeClassification ageClassification,
        String errorCategory,
        int plansAccepted,
        int bandsAccepted,
        int assignmentsAccepted,
        int trueDuplicates,
        int textualFallbacks) {

    public IzelmanImportResult(
            MunicipalSyncRunStatus status,
            String sourceKey,
            String dataType,
            boolean dryRun,
            int recordsRead,
            int accepted,
            int rejected,
            int inserted,
            int updated,
            int unchanged,
            int deactivated,
            SourceAgeClassification ageClassification,
            String errorCategory) {
        this(
                status,
                sourceKey,
                dataType,
                dryRun,
                recordsRead,
                accepted,
                rejected,
                inserted,
                updated,
                unchanged,
                deactivated,
                ageClassification,
                errorCategory,
                0,
                0,
                0,
                0,
                0);
    }
}