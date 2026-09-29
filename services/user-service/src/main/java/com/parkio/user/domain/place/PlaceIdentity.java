package com.parkio.user.domain.place;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Optional namespaced place identity (provider-neutral).
 * Same semantics as parking-service PlaceIdentity; duplicated to avoid
 * cross-service module coupling.
 */
public record PlaceIdentity(String provider, String providerPlaceId) {

    public static final String PROVIDER_OSM_NOMINATIM = "osm-nominatim";

    private static final int MAX_PROVIDER_LENGTH = 64;
    private static final int MAX_PROVIDER_PLACE_ID_LENGTH = 256;
    private static final Pattern PROVIDER_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    public PlaceIdentity {
        provider = normalizeToken(provider, "provider", MAX_PROVIDER_LENGTH);
        providerPlaceId = normalizeToken(providerPlaceId, "providerPlaceId", MAX_PROVIDER_PLACE_ID_LENGTH);
        if (!PROVIDER_PATTERN.matcher(provider).matches()) {
            throw new IllegalArgumentException("provider must be lowercase kebab-case");
        }
    }

    public String canonicalKey() {
        return provider + ":" + providerPlaceId;
    }

    public static PlaceIdentity of(String provider, String providerPlaceId) {
        return new PlaceIdentity(provider, providerPlaceId);
    }

    private static String normalizeToken(String value, String field, int maxLength) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds max length " + maxLength);
        }
        return trimmed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlaceIdentity that)) {
            return false;
        }
        return provider.equals(that.provider) && providerPlaceId.equals(that.providerPlaceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, providerPlaceId);
    }
}
