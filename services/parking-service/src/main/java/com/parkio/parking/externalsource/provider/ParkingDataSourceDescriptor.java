package com.parkio.parking.externalsource.provider;

import java.util.Objects;
import java.util.Set;

/**
 * Canonical descriptor separating provider identity, feed/source key, capabilities,
 * reconciliation policy, and public presentation metadata.
 */
public record ParkingDataSourceDescriptor(
        ParkingDataProviderId providerId,
        String sourceKey,
        String familyKey,
        Set<ProviderCapability> capabilities,
        ReconciliationMode reconciliationMode,
        String displayName,
        String attribution,
        boolean productionEligible) {

    public ParkingDataSourceDescriptor {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(sourceKey, "sourceKey");
        if (sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceKey blank");
        }
        Objects.requireNonNull(familyKey, "familyKey");
        Objects.requireNonNull(capabilities, "capabilities");
        capabilities = Set.copyOf(capabilities);
        Objects.requireNonNull(reconciliationMode, "reconciliationMode");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(attribution, "attribution");
    }

    public boolean supports(ProviderCapability capability) {
        return capabilities.contains(capability);
    }
}
