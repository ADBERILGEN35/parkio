package com.parkio.parking.presentation;

import com.parkio.parking.application.geocoding.GeocodeResult;
import com.parkio.parking.application.geocoding.GeocodingService;
import com.parkio.parking.presentation.dto.GeocodeResultResponse;
import com.parkio.parking.presentation.dto.GeocodeSearchResponse;
import com.parkio.parking.presentation.openapi.StandardApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Anonymous destination geocoding for Public Explore. Proxies through the
 * canonical server-side {@link GeocodingService} (Nominatim) — no browser provider
 * access, no saved places / favourites / recents, no parking data.
 *
 * <p>Gated by the same {@code parkio.public-explore.enabled} surface as public
 * facility discovery. Query parameter policy is fail-closed (unknown / duplicate
 * rejected). Search intent is not shared via HTTP caches ({@code Cache-Control: no-store}).
 */
@Tag(name = "Public Geocoding", description = "Anonymous destination / place / address search")
@StandardApiResponses
@RestController
@RequestMapping("/api/v1/public/geocoding/search")
@ConditionalOnProperty(prefix = "parkio.public-explore", name = "enabled", havingValue = "true")
public class PublicGeocodingController {

    private static final String SEARCH_CACHE = "no-store";
    private static final Set<String> ALLOWED_PARAMS = Set.of("q", "limit");
    /** Must stay aligned with {@link GeocodingService} bounds. */
    private static final int MIN_QUERY_LENGTH = 3;
    private static final int MAX_QUERY_LENGTH = 256;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 10;

    private final GeocodingService geocodingService;

    public PublicGeocodingController(GeocodingService geocodingService) {
        this.geocodingService = geocodingService;
    }

    @Operation(summary = "Search public destinations by free text (anonymous)")
    @GetMapping
    public ResponseEntity<GeocodeSearchResponse> search(HttpServletRequest request) {
        ParsedQuery parsed = parseQuery(request);
        List<GeocodeResult> results;
        try {
            results = geocodingService.search(parsed.query(), parsed.limit());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST, ex.getMessage());
        }
        return ResponseEntity.ok()
                .header("Cache-Control", SEARCH_CACHE)
                .body(new GeocodeSearchResponse(toPublicResults(results)));
    }

    private static ParsedQuery parseQuery(HttpServletRequest request) {
        Map<String, String[]> params = request.getParameterMap();
        for (Map.Entry<String, String[]> entry : params.entrySet()) {
            String name = entry.getKey();
            if (!ALLOWED_PARAMS.contains(name)) {
                throw new ResponseStatusException(
                        BAD_REQUEST, "Unsupported public geocoding query parameter: " + name);
            }
            String[] values = entry.getValue();
            if (values == null || values.length != 1) {
                throw new ResponseStatusException(
                        BAD_REQUEST, "Duplicate or empty query parameter: " + name);
            }
        }
        String rawQuery = single(params, "q");
        if (rawQuery == null) {
            throw new ResponseStatusException(BAD_REQUEST, "q is required");
        }
        String query = rawQuery.trim();
        if (query.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "q must not be blank");
        }
        if (containsControlCharacters(query)) {
            throw new ResponseStatusException(BAD_REQUEST, "q contains unsupported characters");
        }
        if (query.length() < MIN_QUERY_LENGTH || query.length() > MAX_QUERY_LENGTH) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Query must be between " + MIN_QUERY_LENGTH + " and " + MAX_QUERY_LENGTH + " characters.");
        }
        Integer limit = optionalInt(params, "limit");
        if (limit != null && (limit < MIN_LIMIT || limit > MAX_LIMIT)) {
            throw new ResponseStatusException(
                    BAD_REQUEST, "Limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT + ".");
        }
        return new ParsedQuery(query, limit);
    }

    private static List<GeocodeResultResponse> toPublicResults(List<GeocodeResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<GeocodeResultResponse> mapped = new ArrayList<>(results.size());
        for (GeocodeResult result : results) {
            if (result == null) {
                continue;
            }
            if (!isValidCoordinate(result.lat(), result.lng())) {
                continue;
            }
            mapped.add(GeocodeResultResponse.from(result));
        }
        return List.copyOf(mapped);
    }

    private static boolean isValidCoordinate(double lat, double lng) {
        return Double.isFinite(lat)
                && Double.isFinite(lng)
                && lat >= -90.0
                && lat <= 90.0
                && lng >= -180.0
                && lng <= 180.0;
    }

    private static boolean containsControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static Integer optionalInt(Map<String, String[]> params, String name) {
        String raw = single(params, name);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, name + " must be an integer");
        }
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

    private record ParsedQuery(String query, Integer limit) {
    }
}
