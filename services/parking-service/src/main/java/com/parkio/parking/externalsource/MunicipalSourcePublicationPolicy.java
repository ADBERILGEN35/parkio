package com.parkio.parking.externalsource;

import com.parkio.parking.externalsource.izelman.SourceAgeClassification;
import com.parkio.parking.externalsource.izelman.TariffCurrentness;
import com.parkio.parking.infrastructure.config.IzelmanProperties;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Canonical municipal publication decisions based on stable source keys.
 * Controllers must not re-implement these rules.
 */
public final class MunicipalSourcePublicationPolicy {
    private final MunicipalSourceProperties municipal;
    private final IzelmanProperties izelman;

    public MunicipalSourcePublicationPolicy(MunicipalSourceProperties municipal, IzelmanProperties izelman) {
        this.municipal = municipal;
        this.izelman = izelman;
    }

    public boolean isSourceLinkPublishable(String sourceKey) {
        return switch (MunicipalSourceIdentity.familyOf(sourceKey)) {
            case MunicipalSourceIdentity.FAMILY_IZUM -> true;
            case MunicipalSourceIdentity.FAMILY_OSM -> municipal.getOsm().isPublicationEnabled();
            case MunicipalSourceIdentity.FAMILY_IZELMAN -> {
                if (MunicipalSourceIdentity.isIzelmanRoadside(sourceKey)) {
                    yield izelman.isRoadsidePublicationEnabled();
                }
                if (MunicipalSourceIdentity.isIzelmanTariff(sourceKey)) {
                    yield izelman.isTariffPublicationEnabled();
                }
                yield izelman.isFacilityPublicationEnabled();
            }
            default -> false;
        };
    }

    public Set<String> publishableSourceKeys(Set<String> linkedSourceKeys) {
        Set<String> keys = MunicipalSourceIdentity.normalizeKeys(linkedSourceKeys);
        Set<String> out = new LinkedHashSet<>();
        for (String key : keys) {
            if (isSourceLinkPublishable(key)) {
                out.add(key);
            }
        }
        return Set.copyOf(out);
    }

    /**
     * A canonical facility is publishable when at least one linked source key is publishable.
     * Unpublished IZELMAN links must not hide an otherwise publishable IZUM/OSM facility.
     */
    public boolean isFacilityPublishable(Set<String> linkedSourceKeys, String primarySourceKey) {
        Set<String> keys = MunicipalSourceIdentity.normalizeKeys(linkedSourceKeys);
        if (keys.isEmpty() && primarySourceKey != null && !primarySourceKey.isBlank()) {
            keys = Set.of(primarySourceKey.trim());
        }
        if (keys.isEmpty()) {
            return false;
        }
        return !publishableSourceKeys(keys).isEmpty();
    }

    public boolean isRoadsidePublishable(String sourceKey) {
        if (!MunicipalSourceIdentity.isIzelmanRoadside(sourceKey)
                && !MunicipalSourceIdentity.FAMILY_IZELMAN.equals(MunicipalSourceIdentity.familyOf(sourceKey))) {
            return false;
        }
        return izelman.isRoadsidePublicationEnabled();
    }

    public boolean isTariffPublishable(String sourceKey) {
        if (!MunicipalSourceIdentity.isIzelmanTariff(sourceKey)) {
            return false;
        }
        return izelman.isTariffPublicationEnabled();
    }

    /** Live occupancy may only come from a publishable IZUM source link. */
    public boolean mayContributeLiveOccupancy(Set<String> linkedSourceKeys) {
        return publishableSourceKeys(linkedSourceKeys).stream().anyMatch(MunicipalSourceIdentity::isIzum);
    }

    /**
     * Field contribution is independent of facility visibility.
     * IZELMAN inventory fields stay gated while IZELMAN facility publication is false.
     */
    public boolean mayContributeIzelmanInventoryFields(Set<String> linkedSourceKeys) {
        return publishableSourceKeys(linkedSourceKeys).stream()
                .anyMatch(MunicipalSourceIdentity::isIzelmanFacilityInventory);
    }

    public boolean mayContributeOsmFields(Set<String> linkedSourceKeys) {
        return publishableSourceKeys(linkedSourceKeys).stream().anyMatch(MunicipalSourceIdentity::isOsm);
    }

    /**
     * Public tariff currentness never upgrades HISTORICAL/UNKNOWN to CURRENT merely because
     * tariff publication is enabled.
     */
    public TariffCurrentness publicTariffCurrentness(TariffCurrentness stored, SourceAgeClassification age) {
        if (stored == TariffCurrentness.HISTORICAL || age == SourceAgeClassification.HISTORICAL) {
            return TariffCurrentness.HISTORICAL;
        }
        if (stored == TariffCurrentness.UNKNOWN
                || age == SourceAgeClassification.UNKNOWN
                || age == SourceAgeClassification.AGING
                || age == SourceAgeClassification.UNAVAILABLE
                || age == SourceAgeClassification.INVALID) {
            return TariffCurrentness.UNKNOWN;
        }
        return stored == null ? TariffCurrentness.UNKNOWN : stored;
    }
}