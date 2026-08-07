import { isParkioApiError } from '@parkio/api-client';
import type { MunicipalFacility, MunicipalFacilityType } from '@parkio/types';
import {
  municipalAvailabilityCopyKey,
  municipalDataSourceLabels,
  municipalFreshnessCopyKey,
  municipalOccupancyPresentationKind,
  type MunicipalOccupancyPresentationKind,
} from '@parkio/geo';
import { EmptyState, Icon, SoftBadge, Surface, cn } from '@parkio/ui';
import type { ReactNode } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { frontendConfig } from '@/config/env';
import { useAuthStore } from '@/auth/store';
import { useActiveParkingSessionQuery } from '@/data/hooks/useParkingSessionQueries';
import { useMunicipalFacilityDetailQuery } from '@/data/hooks/useParkingQueries';
import { ParkHereAtFacilityButton } from '@/features/parked-car';
import { formatRelativeAgo } from '@/lib/format';
import { formatDistance } from '@/lib/spotDiscovery';
import { isValidRouteParameter } from '@/routing/route-manifest';
import { MunicipalFacilityLocationSection } from './MunicipalFacilityLocationSection';

function facilityTypeLabelKey(type: MunicipalFacilityType): string | null {
  switch (type) {
    case 'ON_STREET':
      return 'municipal.facilityType.onStreet';
    case 'OFF_STREET':
      return 'municipal.facilityType.offStreet';
    case 'UNKNOWN':
    default:
      return null;
  }
}

function parseOptionalDistanceMeters(raw: string | null): number | null {
  if (raw == null || raw === '') return null;
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0) return null;
  return value;
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

function DetailSection({
  title,
  testId,
  headingId,
  children,
}: {
  title: string;
  testId: string;
  headingId: string;
  children: ReactNode;
}) {
  return (
    <Surface level="raised" className="rounded-3xl p-md md:p-lg" data-testid={testId}>
      <h2 id={headingId} className="m-0 mb-md text-title-md text-on-surface">
        {title}
      </h2>
      {children}
    </Surface>
  );
}

function OccupancyMetric({
  value,
  label,
  testId,
}: {
  value: number;
  label: string;
  testId: string;
}) {
  return (
    <div
      className="flex min-w-0 flex-col gap-xs rounded-2xl bg-surface-container/70 px-md py-sm"
      data-testid={testId}
    >
      <span className="text-headline-sm font-semibold tabular-nums text-on-surface">{value}</span>
      <span className="text-label-sm text-on-surface-variant">{label}</span>
    </div>
  );
}

function FacilityOccupancySection({
  facility,
  occupancyKind,
}: {
  facility: MunicipalFacility;
  occupancyKind: MunicipalOccupancyPresentationKind;
}) {
  const { t } = useTranslation('map');
  const availabilityCopyKey = municipalAvailabilityCopyKey(occupancyKind);
  const isLive = occupancyKind === 'live' || occupancyKind === 'aging';
  const observationAt =
    facility.availabilityObservationTimestamp ?? facility.lastUpdatedAt;

  return (
    <DetailSection
      title={t('municipal.detail.occupancyTitle')}
      testId="municipal-facility-occupancy"
      headingId="municipal-facility-occupancy-heading"
    >
      <div className="flex flex-col gap-md">
        <SoftBadge
          tone={isLive ? 'success' : occupancyKind === 'stale_live' ? 'warning' : 'neutral'}
          icon={isLive ? 'sensors' : 'info'}
          className="w-fit"
          data-testid="municipal-occupancy-status"
        >
          {t(`municipal.freshness.${municipalFreshnessCopyKey(occupancyKind)}`)}
        </SoftBadge>

        {isLive && facility.availableSpaces != null ? (
          <div
            className="grid grid-cols-1 gap-sm sm:grid-cols-3"
            data-testid="municipal-availability-copy"
            aria-label={t('municipal.detail.occupancyMetricsAria')}
          >
            <OccupancyMetric
              value={facility.availableSpaces}
              label={t('municipal.detail.spacesOpen')}
              testId="municipal-occupancy-available"
            />
            {facility.occupiedSpaces != null ? (
              <OccupancyMetric
                value={facility.occupiedSpaces}
                label={t('municipal.detail.spacesOccupied')}
                testId="municipal-occupancy-occupied"
              />
            ) : null}
            {facility.capacityTotal != null ? (
              <OccupancyMetric
                value={facility.capacityTotal}
                label={t('municipal.detail.spacesCapacity')}
                testId="municipal-occupancy-capacity"
              />
            ) : null}
          </div>
        ) : (
          <p
            className="m-0 text-body-md text-on-surface"
            data-testid="municipal-availability-copy"
          >
            {occupancyKind === 'stale_live' && facility.capacityTotal != null
              ? `${t(`municipal.${availabilityCopyKey}`)} · ${t('municipal.capacityOnly', {
                  capacity: facility.capacityTotal,
                })}`
              : t(`municipal.${availabilityCopyKey}`)}
          </p>
        )}

        {isLive && observationAt ? (
          <p
            className="m-0 text-label-sm text-on-surface-variant"
            data-testid="municipal-occupancy-updated"
          >
            {t('municipal.detail.updatedAgo', {
              time: formatRelativeAgo(observationAt),
            })}
          </p>
        ) : null}
      </div>
    </DetailSection>
  );
}

function FacilityInfoSection({ facility }: { facility: MunicipalFacility }) {
  const { t } = useTranslation('map');
  const typeKey = facilityTypeLabelKey(facility.facilityType);
  const rows: Array<{ label: string; value: string; testId: string }> = [];

  if (typeKey) {
    rows.push({
      label: t('municipal.facilityTypeLabel'),
      value: t(typeKey),
      testId: 'municipal-facility-type',
    });
  }
  if (facility.operatorName?.trim()) {
    rows.push({
      label: t('municipal.operator'),
      value: facility.operatorName.trim(),
      testId: 'municipal-facility-operator',
    });
  }

  if (rows.length === 0) {
    return null;
  }

  return (
    <DetailSection
      title={t('municipal.detail.facilityInfoTitle')}
      testId="municipal-facility-info"
      headingId="municipal-facility-info-heading"
    >
      <dl className="m-0 flex flex-col gap-sm">
        {rows.map((row) => (
          <div key={row.testId} className="flex flex-col gap-xs sm:flex-row sm:gap-md">
            <dt className="m-0 shrink-0 text-label-sm font-medium text-on-surface-variant sm:w-40">
              {row.label}
            </dt>
            <dd className="m-0 min-w-0 text-body-md text-on-surface" data-testid={row.testId}>
              {row.value}
            </dd>
          </div>
        ))}
      </dl>
    </DetailSection>
  );
}

function FacilitySourceSection({ facility }: { facility: MunicipalFacility }) {
  const { t } = useTranslation('map');
  const dataSourceLabels = municipalDataSourceLabels(facility);
  if (dataSourceLabels.length === 0) {
    return null;
  }
  const headingKey =
    dataSourceLabels.length > 1 ? 'municipal.dataSources' : 'municipal.dataSource';

  return (
    <DetailSection
      title={t(headingKey)}
      testId="municipal-facility-source"
      headingId="municipal-facility-source-heading"
    >
      <ul className="m-0 list-none space-y-xs p-0" data-testid="municipal-facility-source-list">
        {dataSourceLabels.map((label) => (
          <li key={label} className="text-body-md text-on-surface">
            {label}
          </li>
        ))}
      </ul>
    </DetailSection>
  );
}

function FacilityDetailBody({
  facility,
  distanceMeters,
}: {
  facility: MunicipalFacility;
  distanceMeters: number | null;
}) {
  const { t } = useTranslation('map');
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const activeSessionQuery = useActiveParkingSessionQuery({ enabled: isAuthenticated });
  const hasActive =
    isAuthenticated && activeSessionQuery.data?.status === 'ACTIVE';
  const title =
    facility.displayName?.trim() ||
    facility.addressText?.trim() ||
    t('municipal.unnamedFacility');
  const occupancyKind = municipalOccupancyPresentationKind(facility);
  const dataSourceLabels = municipalDataSourceLabels(facility);
  const typeKey = facilityTypeLabelKey(facility.facilityType);

  return (
    <article
      data-testid="municipal-facility-detail"
      aria-labelledby="municipal-facility-detail-title"
      className="flex flex-col gap-lg"
    >
      <header className="flex flex-col gap-sm" data-testid="municipal-facility-header">
        <div className="flex flex-wrap items-center gap-xs">
          <SoftBadge tone="success" icon="garage" className="w-fit">
            {t('municipal.inventoryLabel')}
          </SoftBadge>
          {dataSourceLabels.map((label) => (
            <SoftBadge key={label} tone="neutral" className="w-fit" data-testid="municipal-source-badge">
              {label}
            </SoftBadge>
          ))}
        </div>
        <h1
          id="municipal-facility-detail-title"
          className="m-0 text-headline-md text-on-surface md:text-headline-lg"
        >
          {title}
        </h1>
        <div className="flex flex-wrap items-center gap-x-sm gap-y-xs text-label-sm text-on-surface-variant">
          {distanceMeters != null ? (
            <span
              className="inline-flex items-center gap-xs rounded-full bg-secondary/10 px-sm py-xs font-semibold text-secondary"
              data-testid="municipal-facility-distance"
            >
              <Icon name="near_me" className="text-[14px] leading-none" />
              {formatDistance(distanceMeters)}
            </span>
          ) : null}
          <span
            className="inline-flex items-center gap-xs rounded-full bg-surface-container px-sm py-xs font-semibold"
            data-testid="municipal-facility-status-chip"
          >
            <Icon name="schedule" className="text-[14px] leading-none" />
            {t(`municipal.freshness.${municipalFreshnessCopyKey(occupancyKind)}`)}
          </span>
          {typeKey ? (
            <span className="inline-flex items-center gap-xs rounded-full bg-surface-container px-xs py-xs">
              {t(typeKey)}
            </span>
          ) : null}
        </div>
        {facility.addressText && facility.displayName ? (
          <p className="m-0 text-body-md text-on-surface-variant">{facility.addressText}</p>
        ) : null}
        {isAuthenticated && !hasActive ? (
          <ParkHereAtFacilityButton
            facilityId={facility.id}
            latitude={facility.latitude}
            longitude={facility.longitude}
            displayLabel={title}
            originSurface="municipal_detail"
          />
        ) : null}
      </header>

      <FacilityOccupancySection facility={facility} occupancyKind={occupancyKind} />

      <MunicipalFacilityLocationSection
        facility={facility}
        facilityTitle={title}
        distanceMeters={distanceMeters}
      />

      <FacilityInfoSection facility={facility} />
      <FacilitySourceSection facility={facility} />
    </article>
  );
}

/**
 * Dedicated municipal facility detail (`/facilities/:facilityId`) — WEB-MUNI-02 / WEB-MUNI-10D.
 * Read-only; loads only `GET /parking/facilities/{id}`. Gated by municipal discovery flag.
 * Preview on `/map` remains unchanged aside from optional distance deep-link.
 */
export function MunicipalFacilityDetailPage({
  municipalDiscoveryEnabled = frontendConfig.features.municipalDiscovery,
}: {
  /** Test override for `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED`. */
  municipalDiscoveryEnabled?: boolean;
} = {}) {
  const { t } = useTranslation('map');
  const { facilityId } = useParams<{ facilityId: string }>();
  const [searchParams] = useSearchParams();
  const distanceMeters = parseOptionalDistanceMeters(searchParams.get('d'));
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
        <FacilityDetailBody facility={detailQuery.data} distanceMeters={distanceMeters} />
      ) : null}
    </div>
  );
}
