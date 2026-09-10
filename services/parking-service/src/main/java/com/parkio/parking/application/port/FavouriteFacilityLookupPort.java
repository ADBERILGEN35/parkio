package com.parkio.parking.application.port;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Batch favourite-facility lookup for ranking boosts (WP-SPA-06).
 *
 * <p>Implementations must fail open (empty set) on transport / remote errors.
 * Never throws for ranking-path failures.
 */
public interface FavouriteFacilityLookupPort {

    /**
     * Returns the subset of {@code facilityIds} that the user has favourited
     * as municipal parking facilities. Empty on failure or when disabled.
     */
    Set<UUID> favouritedMunicipalFacilityIds(UUID authUserId, Collection<UUID> facilityIds);
}
