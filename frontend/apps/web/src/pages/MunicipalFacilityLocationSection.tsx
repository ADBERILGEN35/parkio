import type { MunicipalFacility } from '@parkio/types';
import { Button, Surface } from '@parkio/ui';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { MunicipalMapErrorBoundary } from '@/components/map/MunicipalMapErrorBoundary';
import { isUsableParkedCoordinate } from '@/components/map/parkedCarCoords';
import { SpotMap } from '@/components/map/SpotMap';
import { openParkingLocationInMaps } from '@/components/parking/openParkingMaps';
import { formatDistance } from '@/lib/spotDiscovery';
import { showError } from '@/lib/toast';

export type MunicipalFacilityLocationSectionProps = {
  facility: MunicipalFacility;
  /** Resolved display title used for accessible map naming. */
  facilityTitle: string;
  /** Optional distance from the discovery search center (meters). */
  distanceMeters?: number | null;
};

/**
 * Read-only location block for municipal facility detail (WEB-MUNI-04 / WEB-MUNI-10D).
 * Consumes coordinates already on the facility DTO — no extra Parkio requests.
 */
export function MunicipalFacilityLocationSection({
  facility,
  facilityTitle,
  distanceMeters = null,
}: MunicipalFacilityLocationSectionProps) {
  const { t } = useTranslation('map');
  const coordsValid = isUsableParkedCoordinate(facility.latitude, facility.longitude);
  const [mapFailed, setMapFailed] = useState(false);
  const mapAria = t('municipal.detail.mapAria', { name: facilityTitle });
  const address = facility.addressText?.trim() || null;

  const openInMaps = () => {
    if (!coordsValid) {
      return;
    }
    if (!openParkingLocationInMaps(facility.latitude, facility.longitude)) {
      showError(t('parkingSession.maps.failed'));
    }
  };

  const mapUnavailable = (
    <p
      className="m-0 rounded-2xl bg-surface-container px-md py-lg text-body-md text-on-surface-variant"
      data-testid="municipal-facility-map-unavailable"
      role="status"
    >
      {t('municipal.detail.mapUnavailable')}
    </p>
  );

  return (
    <Surface
      level="raised"
      className="rounded-3xl p-md md:p-lg"
      data-testid="municipal-facility-location"
    >
      <div className="flex flex-col gap-md">
        <div className="flex flex-col gap-sm sm:flex-row sm:items-start sm:justify-between sm:gap-md">
          <h2
            id="municipal-facility-location-heading"
            className="m-0 text-title-md text-on-surface"
          >
            {t('municipal.detail.locationTitle')}
          </h2>
          {coordsValid ? (
            <Button
              type="button"
              variant="secondary"
              className="w-full shrink-0 sm:w-auto"
              onClick={openInMaps}
              aria-label={t('municipal.detail.openInMapsA11y')}
              data-testid="municipal-facility-open-in-maps"
            >
              {t('municipal.detail.openInMaps')}
              <span className="sr-only">{t('municipal.detail.openInMapsExternal')}</span>
            </Button>
          ) : null}
        </div>

        {(address || distanceMeters != null) && (
          <dl className="m-0 flex flex-col gap-sm">
            {address ? (
              <div className="flex flex-col gap-xs sm:flex-row sm:gap-md">
                <dt className="m-0 shrink-0 text-label-sm font-medium text-on-surface-variant sm:w-40">
                  {t('municipal.detail.addressLabel')}
                </dt>
                <dd
                  className="m-0 min-w-0 text-body-md text-on-surface"
                  data-testid="municipal-facility-address"
                >
                  {address}
                </dd>
              </div>
            ) : null}
            {distanceMeters != null ? (
              <div className="flex flex-col gap-xs sm:flex-row sm:gap-md">
                <dt className="m-0 shrink-0 text-label-sm font-medium text-on-surface-variant sm:w-40">
                  {t('municipal.detail.distanceLabel')}
                </dt>
                <dd
                  className="m-0 min-w-0 text-body-md text-on-surface"
                  data-testid="municipal-facility-location-distance"
                >
                  {formatDistance(distanceMeters)}
                </dd>
              </div>
            ) : null}
          </dl>
        )}

        {!coordsValid ? (
          <p
            className="m-0 rounded-2xl bg-surface-container px-md py-lg text-body-md text-on-surface-variant"
            data-testid="municipal-facility-location-unavailable"
            role="status"
          >
            {t('municipal.detail.locationUnavailable')}
          </p>
        ) : mapFailed ? (
          mapUnavailable
        ) : (
          <MunicipalMapErrorBoundary
            fallback={mapUnavailable}
            onError={() => setMapFailed(true)}
          >
            <div className="max-h-[320px] min-h-[200px] w-full max-w-full overflow-hidden rounded-3xl shadow-deep ring-1 ring-outline-variant/20">
              <SpotMap
                latitude={facility.latitude}
                longitude={facility.longitude}
                height={240}
                ariaLabel={mapAria}
                markerPresentation="municipal"
                onError={() => setMapFailed(true)}
              />
            </div>
          </MunicipalMapErrorBoundary>
        )}
      </div>
    </Surface>
  );
}
