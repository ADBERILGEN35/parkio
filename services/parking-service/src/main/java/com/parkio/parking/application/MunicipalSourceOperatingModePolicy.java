package com.parkio.parking.application;

import com.parkio.parking.externalsource.MunicipalSourceIdentity;
import com.parkio.parking.externalsource.MunicipalSourceOperatingMode;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.util.Objects;

/**
 * Centralized source-key → operating-mode resolution (DATA-WP-16).
 *
 * <p>Modes come from explicit typed configuration on known sources. Unknown keys fail
 * closed to {@link MunicipalSourceOperatingMode#SCHEDULED} so age thresholds still apply
 * rather than silently suppressing SLA.
 */
public final class MunicipalSourceOperatingModePolicy {
    private MunicipalSourceOperatingModePolicy() {}

    public static MunicipalSourceOperatingMode resolve(
            String sourceKey, MunicipalSourceProperties properties) {
        Objects.requireNonNull(properties, "properties");
        if (MunicipalSourceIdentity.isIzum(sourceKey)) {
            return properties.getIzum().getOperatingMode();
        }
        if (MunicipalSourceIdentity.isIspark(sourceKey)) {
            return properties.getIspark().getOperatingMode();
        }
        if (MunicipalSourceIdentity.isAnpark(sourceKey)) {
            return properties.getAnpark().getOperatingMode();
        }
        if (MunicipalSourceIdentity.isKonya(sourceKey)) {
            return properties.getKonya().getOperatingMode();
        }
        if (MunicipalSourceIdentity.isKayseri(sourceKey)) {
            return properties.getKayseri().getOperatingMode();
        }
        if (MunicipalSourceIdentity.isOsm(sourceKey)) {
            return properties.getOsm().getOperatingMode();
        }
        if (MunicipalSourceIdentity.isIzelman(sourceKey)) {
            // Inventory-only / unpublished while flags remain false.
            return MunicipalSourceOperatingMode.OPERATOR_IMPORTED;
        }
        return MunicipalSourceOperatingMode.SCHEDULED;
    }

    public static boolean applySecondsSinceSuccessThresholds(
            MunicipalSourceOperatingMode mode, boolean sourceModeSlaEnabled) {
        if (!sourceModeSlaEnabled) {
            return true;
        }
        return mode == MunicipalSourceOperatingMode.SCHEDULED;
    }
}
