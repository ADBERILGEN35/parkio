package com.parkio.parking.presentation;

import com.parkio.parking.application.PublicExploreQueryService;
import com.parkio.parking.presentation.dto.PublicExploreDiscoveryResponse;
import com.parkio.parking.presentation.dto.PublicExploreFacilityMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/v1/public/explore/facilities")
@ConditionalOnProperty(prefix = "parkio.public-explore", name = "enabled", havingValue = "true")
public class PublicExploreController {
    /**
     * Location-aware responses must not be shared across users/CDNs keyed only by path.
     * Query string is part of browser cache keys, but {@code private} is fail-closed.
     */
    private static final String LIST_CACHE = "private, max-age=30, stale-while-revalidate=120";
    private static final Set<String> ALLOWED_PARAMS =
            Set.of("lat", "lng", "radiusMeters", "limit");

    private final PublicExploreQueryService service;

    public PublicExploreController(PublicExploreQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<PublicExploreDiscoveryResponse> list(HttpServletRequest request) {
        PublicExploreQueryService.DiscoveryQuery query = parseQuery(request);
        PublicExploreQueryService.DiscoveryResult result;
        try {
            result = service.discover(query);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        }
        return ResponseEntity.ok()
                .header("Cache-Control", LIST_CACHE)
                .body(new PublicExploreDiscoveryResponse(
                        result.facilities().stream().map(PublicExploreFacilityMapper::from).toList(),
                        result.municipalTotalInScope(),
                        result.municipalHiddenCount(),
                        result.communitySpotCountInScope()));
    }

    private static PublicExploreQueryService.DiscoveryQuery parseQuery(HttpServletRequest request) {
        Map<String, String[]> params = request.getParameterMap();
        for (Map.Entry<String, String[]> entry : params.entrySet()) {
            String name = entry.getKey();
            if (!ALLOWED_PARAMS.contains(name)) {
                throw new ResponseStatusException(
                        BAD_REQUEST, "Unsupported public explore query parameter: " + name);
            }
            String[] values = entry.getValue();
            if (values == null || values.length != 1) {
                throw new ResponseStatusException(
                        BAD_REQUEST, "Duplicate or empty query parameter: " + name);
            }
        }
        return new PublicExploreQueryService.DiscoveryQuery(
                optionalDouble(params, "lat"),
                optionalDouble(params, "lng"),
                optionalInt(params, "radiusMeters"),
                optionalInt(params, "limit"));
    }

    private static Double optionalDouble(Map<String, String[]> params, String name) {
        String raw = single(params, name);
        if (raw == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value)) {
                throw new ResponseStatusException(BAD_REQUEST, name + " must be a finite number");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(BAD_REQUEST, name + " must be a number");
        }
    }

    private static Integer optionalInt(Map<String, String[]> params, String name) {
        String raw = single(params, name);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.contains(".") || trimmed.contains("e") || trimmed.contains("+")) {
            throw new ResponseStatusException(BAD_REQUEST, name + " must be an integer");
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(BAD_REQUEST, name + " must be an integer");
        }
    }

    private static String single(Map<String, String[]> params, String name) {
        String[] values = params.get(name);
        if (values == null) {
            return null;
        }
        return values[0];
    }
}
