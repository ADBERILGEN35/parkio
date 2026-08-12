package com.parkio.parking.presentation;

import com.parkio.parking.application.MunicipalFacilitySyncService;
import com.parkio.parking.externalsource.MunicipalSyncResult;
import com.parkio.parking.infrastructure.anpark.AnparkMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import com.parkio.parking.infrastructure.ispark.IsparkMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.izum.IzumMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.konya.KonyaMunicipalParkingAdapter;
import com.parkio.parking.infrastructure.metrics.MunicipalSourceMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/parking/municipal/sources")
@ConditionalOnProperty(name = {
        "parkio.municipal.enabled",
        "parkio.municipal.manual-sync-enabled"
}, havingValue = "true")
public class MunicipalManualSyncController {
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "SUPER_ADMIN");
    private final MunicipalFacilitySyncService service;
    private final MunicipalSourceMetrics metrics;
    private final MunicipalSourceProperties properties;

    public MunicipalManualSyncController(
            MunicipalFacilitySyncService service,
            MunicipalSourceMetrics metrics,
            MunicipalSourceProperties properties) {
        this.service = service;
        this.metrics = metrics;
        this.properties = properties;
    }

    @PostMapping("/{sourceKey}/sync")
    @ResponseStatus(HttpStatus.OK)
    public MunicipalSyncResult sync(
            @PathVariable String sourceKey,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        boolean admin = roles != null && Arrays.stream(roles.split(","))
                .map(String::trim).map(value -> value.toUpperCase(Locale.ROOT)).anyMatch(ADMIN_ROLES::contains);
        if (!admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        if (IzumMunicipalParkingAdapter.SOURCE_KEY.equals(sourceKey) && !properties.getIzum().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IZUM source is disabled");
        }
        if (IsparkMunicipalParkingAdapter.SOURCE_KEY.equals(sourceKey) && !properties.getIspark().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ISPARK source is disabled");
        }
        if (AnparkMunicipalParkingAdapter.SOURCE_KEY.equals(sourceKey) && !properties.getAnpark().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ANPARK source is disabled");
        }
        if (KonyaMunicipalParkingAdapter.SOURCE_KEY.equals(sourceKey) && !properties.getKonya().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KONYA source is disabled");
        }
        Instant started = Instant.now();
        try {
            MunicipalSyncResult result = service.sync(sourceKey);
            metrics.record(sourceKey, result, Duration.between(started, Instant.now()));
            return result;
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknown.getMessage());
        }
    }
}