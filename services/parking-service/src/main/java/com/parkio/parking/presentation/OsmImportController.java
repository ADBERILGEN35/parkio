package com.parkio.parking.presentation;

import com.parkio.parking.application.OsmImportApplicationService;
import com.parkio.parking.application.OsmImportResult;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.metrics.MunicipalSourceMetrics;
import com.parkio.parking.infrastructure.metrics.OsmImportMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-only OSM interchange import. Accepts configured local path only — never arbitrary URLs.
 */
@RestController
@RequestMapping("/api/v1/parking/municipal/sources/osm-geofabrik-turkey")
@ConditionalOnProperty(name = {
        "parkio.municipal.enabled",
        "parkio.municipal.osm.import-enabled"
}, havingValue = "true")
public class OsmImportController {
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "SUPER_ADMIN");
    private final OsmImportApplicationService service;
    private final OsmImportMetrics metrics;
    private final MunicipalSourceMetrics municipalSourceMetrics;
    private final MunicipalSourceProperties properties;

    public OsmImportController(
            OsmImportApplicationService service,
            OsmImportMetrics metrics,
            MunicipalSourceMetrics municipalSourceMetrics,
            MunicipalSourceProperties properties) {
        this.service = service;
        this.metrics = metrics;
        this.municipalSourceMetrics = municipalSourceMetrics;
        this.properties = properties;
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.OK)
    public OsmImportResult importOsm(
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        boolean admin = roles != null && Arrays.stream(roles.split(","))
                .map(String::trim).map(v -> v.toUpperCase(Locale.ROOT)).anyMatch(ADMIN_ROLES::contains);
        if (!admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
        if (!properties.getOsm().isImportEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "OSM import is disabled");
        }
        Instant started = Instant.now();
        try {
            OsmImportResult result = service.importFromConfiguredPath(dryRun);
            metrics.record(result, Duration.between(started, Instant.now()));
            municipalSourceMetrics.refreshOsmFromHistory();
            return result;
        } catch (IllegalArgumentException ex) {
            municipalSourceMetrics.refreshOsmFromHistory();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            municipalSourceMetrics.refreshOsmFromHistory();
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }
}