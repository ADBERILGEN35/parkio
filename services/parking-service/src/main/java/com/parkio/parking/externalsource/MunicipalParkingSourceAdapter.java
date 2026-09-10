package com.parkio.parking.externalsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkio.parking.externalsource.provider.ParkingDataProviderId;
import com.parkio.parking.externalsource.provider.ParkingProviderCatalog;
import com.parkio.parking.externalsource.provider.ProviderCapability;
import com.parkio.parking.externalsource.provider.ReconciliationMode;
import com.parkio.parking.externalsource.schema.SchemaFingerprint;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Live parking-source adapter boundary. Provider-specific HTTP/parse logic stays here;
 * adapters must not persist, call recommendation/ranking, or format UI copy.
 */
public interface MunicipalParkingSourceAdapter {
    String sourceKey();

    default ParkingDataProviderId providerId() {
        return ParkingProviderCatalog.find(sourceKey())
                .map(d -> d.providerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Adapter missing provider catalog entry for source: " + sourceKey()));
    }

    default Set<ProviderCapability> capabilities() {
        return ParkingProviderCatalog.find(sourceKey())
                .map(d -> d.capabilities())
                .orElseThrow(() -> new IllegalStateException(
                        "Adapter missing provider catalog entry for source: " + sourceKey()));
    }

    default ReconciliationMode reconciliationMode() {
        return ParkingProviderCatalog.find(sourceKey())
                .map(d -> d.reconciliationMode())
                .orElse(ReconciliationMode.UPSERT_ONLY);
    }

    JsonNode fetch();

    SchemaFingerprint validateContract(JsonNode payload);

    List<NormalizedMunicipalFacility> normalizeFacilities(JsonNode payload, Instant fetchedAt);

    List<NormalizedMunicipalOccupancy> normalizeOccupancy(JsonNode payload, Instant fetchedAt);

    /**
     * Provider-specific "trustworthiness" signal for reconciliation.
     *
     * <p>For {@link ReconciliationMode#AUTHORITATIVE_FULL_SET} we must be able to distinguish:
     * <ul>
     *   <li>an empty/unusable authoritative feed (including {@code []})</li>
     *   <li>a successfully fetched, structurally-valid authoritative feed where the intended active
     *       set may legitimately be empty (e.g. all upstream facilities are {@code active=false})</li>
     * </ul>
     *
     * <p>The default implementation equates "trustworthy valid members" with whatever the adapter
     * would normalize into active facilities.
     */
    default int countAuthoritativeValidUniqueFacilityExternalIds(JsonNode payload) {
        // This default preserves current behavior for adapters that do not have an "active=false
        // filtering" concept: valid != accepted implies 0 trustworthiness and reconciliation is skipped.
        return normalizeFacilities(payload, Instant.EPOCH).size();
    }
}
