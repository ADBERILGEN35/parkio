package com.parkio.parking.presentation;

import com.parkio.parking.application.quality.MunicipalQualityReport;
import com.parkio.parking.application.quality.MunicipalQualityReportService;
import com.parkio.parking.application.quality.SourceQualityDetail;
import com.parkio.parking.application.quality.UnknownQualityReportSourceException;
import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.infrastructure.metrics.MunicipalQualityReportMetrics;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only municipal quality/coverage report for operators (DATA-WP-15).
 * Registered only when the kill-switch is on; disabled is HTTP 404 for the whole path.
 * Exposes no sync, import or linking trigger.
 */
@RestController
@RequestMapping("/api/v1/parking/admin/municipal/quality-report")
@ConditionalOnProperty(name = "parkio.municipal.ops.quality-report-enabled", havingValue = "true")
public class MunicipalQualityReportController {
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "SUPER_ADMIN");

    private final MunicipalQualityReportService service;
    private final MunicipalQualityReportMetrics metrics;

    public MunicipalQualityReportController(
            MunicipalQualityReportService service, MunicipalQualityReportMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @GetMapping({"", "/"})
    public MunicipalQualityReport overall(
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        requireAdmin(roles);
        long started = System.nanoTime();
        try {
            MunicipalQualityReport report = service.overallReport();
            record(MunicipalQualityReportMetrics.TYPE_OVERALL,
                    MunicipalQualityReportMetrics.OUTCOME_SUCCESS,
                    MunicipalQualityReportMetrics.FAMILY_NONE, started);
            return report;
        } catch (RuntimeException ex) {
            record(MunicipalQualityReportMetrics.TYPE_OVERALL,
                    MunicipalQualityReportMetrics.OUTCOME_ERROR,
                    MunicipalQualityReportMetrics.FAMILY_NONE, started);
            throw ex;
        }
    }

    @GetMapping("/sources/{sourceKey}")
    public SourceQualityDetail source(
            @PathVariable String sourceKey,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        requireAdmin(roles);
        String family = MunicipalSourceIdentity.familyOf(sourceKey);
        long started = System.nanoTime();
        try {
            SourceQualityDetail detail = service.sourceReport(sourceKey, limit);
            record(MunicipalQualityReportMetrics.TYPE_SOURCE,
                    MunicipalQualityReportMetrics.OUTCOME_SUCCESS, family, started);
            return detail;
        } catch (UnknownQualityReportSourceException unknown) {
            record(MunicipalQualityReportMetrics.TYPE_SOURCE,
                    MunicipalQualityReportMetrics.OUTCOME_NOT_FOUND, family, started);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknown.getMessage(), unknown);
        } catch (IllegalArgumentException invalid) {
            record(MunicipalQualityReportMetrics.TYPE_SOURCE,
                    MunicipalQualityReportMetrics.OUTCOME_CLIENT_ERROR, family, started);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        } catch (RuntimeException ex) {
            record(MunicipalQualityReportMetrics.TYPE_SOURCE,
                    MunicipalQualityReportMetrics.OUTCOME_ERROR, family, started);
            throw ex;
        }
    }

    private void record(String reportType, String outcome, String sourceFamily, long startedNanos) {
        metrics.record(reportType, outcome, sourceFamily,
                (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static void requireAdmin(String roles) {
        if (roles == null || roles.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        boolean admin = Arrays.stream(roles.split(","))
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(ADMIN_ROLES::contains);
        if (!admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
    }
}
