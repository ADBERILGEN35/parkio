import { isParkioApiError } from '@parkio/api-client';
import type { MunicipalFacility, MunicipalFacilityType, MunicipalOccupancyFreshness } from '@parkio/types';
import { EmptyState, Icon, SoftBadge, Surface, cn } from '@parkio/ui';
import type { ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { isUsableParkedCoordinate } from '@/components/map/parkedCarCoords';
import { frontendConfig } from '@/config/env';
import { useMunicipalFacilityDetailQuery } from '@/data/hooks/useParkingQueries';
import { formatInstant } from '@/lib/format';
import { isValidRouteParameter } from '@/routing/route-manifest';
import { MunicipalFacilityLocationSection } from './MunicipalFacilityLocationSection';

function freshnessLabelKey(freshness: MunicipalOccupancyFreshness | null | undefined): string {
  switch (freshness) {
    case 'LIVE':
      return 'municipal.freshness.live';
    case 'AGING':
      return 'municipal.freshness.aging';
    case 'STALE':
      return 'municipal.freshness.stale';
    case 'INVALID':
      return 'municipal.freshness.invalid';
    case 'UNAVAILABLE':
    default:
      return 'municipal.freshness.unavailable';
  }
}

function facilityTypeLabelKey(type: MunicipalFacilityType): string {
  switch (type) {
    case 'ON_STREET':
      return 'municipal.facilityType.onStreet';
    case 'OFF_STREET':
      return 'municipal.facilityType.offStreet';
    case 'UNKNOWN':
    default:
      return 'municipal.facilityType.unknown';
  }
}

function FacilityDetailSkeleton() {
  return (
    <div
      className="flex flex-col gap-md"
      role="status"
      aria-busy="true"
      data-testid="municipal-facility-detail-loading"
    >
      <div className="h-8 w-2/3 animate-pulse rounded-xl bg-surface-container" />
      <div className="h-40 animate-pulse rounded-3xl bg-surface-container" />
      <div className="h-24 animate-pulse rounded-3xl bg-surface-container" />
    </div>
  );
}

function DetailRow({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-xs border-b border-outline-variant/20 py-sm last:border-b-0 sm:flex-row sm:gap-md">
      <dt className="m-0 shrink-0 text-label-sm font-medium text-on-surface-variant sm:w-40">
        {label}
      </dt>
      <dd className="m-0 min-w-0 text-body-md text-on-surface">{children}</dd>
    </div>
  );
}

function FacilityDetailBody({ facility }: { facility: MunicipalFacility }) {
  const { t } = useTranslation('map');
  const title =
    facility.displayName?.trim() ||
    facility.addressText?.trim() ||
    t('municipal.unnamedFacility');
  const freshness = facility.availabilityFreshness ?? facility.freshness;
  const provenanceEntries = Object.entries(facility.selectedFieldProvenanceSummary ?? {});

  return (
    <article
      data-testid="municipal-facility-detail"
      aria-labelledby="municipal-facility-detail-title"
      className="flex flex-col gap-lg"
    >
      <header className="flex flex-col gap-sm">
        <SoftBadge tone="success" icon="garage" className="w-fit">
          {t('municipal.inventoryLabel')}
        </SoftBadge>
        <h1
          id="municipal-facility-detail-title"
          className="m-0 text-headline-md text-on-surface md:text-headline-lg"
        >
          {title}
        </h1>
        {facility.addressText && facility.displayName ? (
          <p className="m-0 text-body-md text-on-surface-variant">{facility.addressText}</p>
        ) : null}
      </header>

      <Surface level="raised" className="rounded-3xl p-md md:p-lg">
        <dl className="m-0">
          <DetailRow label={t('municipal.facilityTypeLabel')}>
            {t(facilityTypeLabelKey(facility.facilityType))}
          </DetailRow>
          <DetailRow label={t('municipal.freshnessLabel')}>
            {t(freshnessLabelKey(freshness))}
          </DetailRow>
          <DetailRow label={t('municipal.availability')}>
            {facility.availableSpaces != null || facility.capacityTotal != null
              ? facility.availableSpaces != null
                ? t('municipal.spacesAvailable', {
                    available: facility.availableSpaces,
                    capacity: facility.capacityTotal ?? '—',
                  })
                : t('municipal.capacityOnly', { capacity: facility.capacityTotal })
              : t('municipal.availabilityUnknown')}
          </DetailRow>
          {facility.capacityTotal != null ? (
            <DetailRow label={t('municipal.capacity')}>{facility.capacityTotal}</DetailRow>
          ) : null}
          {facility.operatorName ? (
            <DetailRow label={t('municipal.operator')}>{facility.operatorName}</DetailRow>
          ) : null}
          {facility.sourceLabel || facility.attribution ? (
            <DetailRow label={t('municipal.source')}>
              {facility.sourceLabel ?? facility.attribution}
              {facility.attribution && facility.sourceLabel
                ? ` · ${facility.attribution}`
                : null}
            </DetailRow>
          ) : null}
          <DetailRow label={t('municipal.coordinates')}>
            {isUsableParkedCoordinate(facility.latitude, facility.longitude) ? (
              <span className="font-mono text-label-md">
                {facility.latitude.toFixed(6)}, {facility.longitude.toFixed(6)}
              </span>
            ) : (
              <span className="text-on-surface-variant">
                {t('municipal.detail.locationUnavailable')}
              </span>
            )}
          </DetailRow>
          {facility.lastUpdatedAt ? (
            <DetailRow label={t('municipal.lastUpdated')}>
              {formatInstant(facility.lastUpdatedAt)}
            </DetailRow>
          ) : null}
        </dl>
      </Surface>

      <MunicipalFacilityLocationSection facility={facility} facilityTitle={title} />

      {provenanceEntries.length > 0 ? (
        <Surface level="raised" className="rounded-3xl p-md md:p-lg">
          <h2 className="m-0 text-title-md text-on-surface">{t('municipal.provenance')}</h2>
          <ul className="m-0 mt-sm list-none space-y-sm p-0">
            {provenanceEntries.map(([field, source]) => (
              <li
                key={field}
                className="flex justify-between gap-md border-b border-outline-variant/20 py-xs text-label-md last:border-b-0"
              >
                <span className="text-on-surface-variant">{field}</span>
                <span className="truncate font-medium text-on-surface">{source}</span>
              </li>
            ))}
          </ul>
        </Surface>
      ) : null}
    </article>
  );
}

/**
 * Dedicated municipal facility detail (`/facilities/:facilityId`) — WEB-MUNI-02.
 * Read-only; loads only `GET /parking/facilities/{id}`. Gated by municipal discovery flag.
 * Preview on `/map` remains unchanged.
 */
export function MunicipalFacilityDetailPage({
  municipalDiscoveryEnabled = frontendConfig.features.municipalDiscovery,
}: {
  /** Test override for `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED`. */
  municipalDiscoveryEnabled?: boolean;
} = {}) {
  const { t } = useTranslation('map');
  const { facilityId } = useParams<{ facilityId: string }>();
  const idValid = Boolean(facilityId && isValidRouteParameter('uuid', facilityId));
  const detailQuery = useMunicipalFacilityDetailQuery(idValid ? facilityId! : null, {
    enabled: municipalDiscoveryEnabled && idValid,
  });

  return (
    <div className="mx-auto w-full max-w-3xl px-md py-lg text-on-background md:px-xl">
      <nav className="mb-lg">
        <Link
          to="/map"
          className={cn(
            'inline-flex items-center gap-xs rounded-full px-sm py-xs text-label-md',
            'text-on-surface-variant no-underline transition-colors duration-std',
            'hover:bg-surface-container hover:text-primary',
          )}
        >
          <Icon name="arrow_back" className="text-[16px] leading-none" />
          {t('municipal.detail.backToMap')}
        </Link>
      </nav>

      {!municipalDiscoveryEnabled ? (
        <div data-testid="municipal-facility-detail-disabled">
          <Surface level="raised" className="rounded-3xl p-xl">
            <EmptyState
              icon="garage"
              title={t('municipal.detail.disabledTitle')}
              description={t('municipal.detail.disabledDescription')}
            />
          </Surface>
        </div>
      ) : !idValid ? (
        <div data-testid="municipal-facility-detail-invalid">
          <Surface level="raised" className="rounded-3xl p-xl">
            <EmptyState
              icon="search_off"
              title={t('municipal.detail.invalidIdTitle')}
              description={t('municipal.detail.invalidIdDescription')}
            />
          </Surface>
        </div>
      ) : detailQuery.isPending ? (
        <FacilityDetailSkeleton />
      ) : detailQuery.isError ? (
        <div data-testid="municipal-facility-detail-error">
          <Surface level="raised" className="rounded-3xl p-xl">
            {isParkioApiError(detailQuery.error) && detailQuery.error.status === 404 ? (
              <div data-testid="municipal-facility-detail-not-found">
                <EmptyState
                  icon="search_off"
                  title={t('municipal.detail.notFoundTitle')}
                  description={t('municipal.detail.notFoundDescription')}
                />
              </div>
            ) : (
              <FriendlyApiErrorMessage error={detailQuery.error} />
            )}
          </Surface>
        </div>
      ) : detailQuery.data ? (
        <FacilityDetailBody facility={detailQuery.data} />
      ) : null}
    </div>
  );
}
