package com.parkio.parking.presentation;

import com.parkio.parking.application.IzelmanImportApplicationService;
import com.parkio.parking.application.IzelmanImportResult;
import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.infrastructure.metrics.IzelmanImportMetrics;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/parking/municipal/sources")
@ConditionalOnProperty(name = {"parkio.municipal.enabled", "parkio.municipal.izelman.enabled"}, havingValue = "true")
public class IzelmanImportController {
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "SUPER_ADMIN");
    private final IzelmanImportApplicationService service;
    private final IzelmanImportMetrics metrics;

    public IzelmanImportController(IzelmanImportApplicationService service, IzelmanImportMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @PostMapping("/{sourceKey}/izelman-import")
    public IzelmanImportResult importCsv(
            @PathVariable String sourceKey,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        if (!isAdmin(roles)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        if (!IzelmanSourceKeys.ALL.contains(sourceKey))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown İZELMAN source");
        try {
            IzelmanImportResult result = service.importConfigured(sourceKey, dryRun);
            metrics.record(result);
            return result;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    private static boolean isAdmin(String roles) {
        return roles != null && Arrays.stream(roles.split(",")).map(String::trim)
                .map(v -> v.toUpperCase(Locale.ROOT)).anyMatch(ADMIN_ROLES::contains);
    }
}
