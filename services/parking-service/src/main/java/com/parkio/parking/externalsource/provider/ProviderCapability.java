package com.parkio.parking.externalsource.provider;

/**
 * Declared capabilities of a parking data source. Behaviour should prefer these over
 * hard-coded {@code if (source == IZUM)} branches where practical.
 */
public enum ProviderCapability {
    FACILITY_INVENTORY,
    LIVE_OCCUPANCY
}
