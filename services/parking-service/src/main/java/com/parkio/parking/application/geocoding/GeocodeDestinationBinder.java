package com.parkio.parking.application.geocoding;

import com.parkio.parking.domain.place.Destination;
import com.parkio.parking.domain.place.DestinationSource;
import com.parkio.parking.domain.place.PlaceIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Canonical mapper: {@link GeocodeResult} → {@link Destination}.
 *
 * <p>Provider-neutral at the Destination boundary. Nominatim {@code place_id}
 * values become a namespaced {@link PlaceIdentity}; coordinate-fallback ids
 * produced by the Nominatim adapter yield {@code null} identity (coordinates
 * remain the identity basis). Invalid candidates are skipped — they never
 * crash a search list.
 *
 * <p>Does not mutate geocode results or leak raw provider payloads.
 */
@Component
public class GeocodeDestinationBinder {

    private static final Logger log = LoggerFactory.getLogger(GeocodeDestinationBinder.class);

    /**
     * Nominatim adapter falls back to {@code lat + "," + lng} when place_id is
     * missing. Those are not stable provider identities.
     */
    private static final Pattern COORDINATE_FALLBACK_ID =
            Pattern.compile("^-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?$");

    /**
     * Bind a single geocode candidate. Returns empty when the candidate cannot
     * form a valid Destination (blank label, invalid coordinates).
     */
    public Optional<Destination> bind(GeocodeResult result) {
        if (result == null) {
            return Optional.empty();
        }
        try {
            String label = resolveLabel(result);
            String subtitle = blankToNull(result.secondary());
            PlaceIdentity identity = resolveIdentity(result).orElse(null);
            return Optional.of(Destination.of(
                    label,
                    result.lat(),
                    result.lng(),
                    DestinationSource.GEOCODING,
                    identity,
                    subtitle));
        } catch (IllegalArgumentException ex) {
            log.debug("skipping invalid geocode candidate: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Bind many candidates; invalid rows are omitted in order. */
    public List<Destination> bindAll(List<GeocodeResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<Destination> destinations = new ArrayList<>(results.size());
        for (GeocodeResult result : results) {
            bind(result).ifPresent(destinations::add);
        }
        return List.copyOf(destinations);
    }

    private static String resolveLabel(GeocodeResult result) {
        String primary = blankToNull(result.primary());
        if (primary != null) {
            return primary;
        }
        String displayName = blankToNull(result.displayName());
        if (displayName != null) {
            return displayName;
        }
        throw new IllegalArgumentException("label must not be blank");
    }

    private static Optional<PlaceIdentity> resolveIdentity(GeocodeResult result) {
        String id = blankToNull(result.id());
        if (id == null) {
            return Optional.empty();
        }
        if (isCoordinateFallbackId(id, result.lat(), result.lng())) {
            return Optional.empty();
        }
        try {
            return Optional.of(PlaceIdentity.osmNominatim(id));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    static boolean isCoordinateFallbackId(String id, double lat, double lng) {
        if (COORDINATE_FALLBACK_ID.matcher(id).matches()) {
            return true;
        }
        // Exact fallback string used by NominatimGeocodingProvider.
        return id.equals(lat + "," + lng);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
