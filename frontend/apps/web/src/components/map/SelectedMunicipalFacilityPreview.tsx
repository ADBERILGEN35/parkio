import type { MunicipalFacility } from '@parkio/types';
import {
  formatMunicipalDataSourcesLine,
  municipalAvailabilityCopyKey,
  municipalDataSourceLabels,
  municipalFreshnessCopyKey,
  municipalOccupancyPresentationKind,
} from '@parkio/geo';
import { Icon, IconButton, SoftBadge, cn } from '@parkio/ui';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { formatDistance } from '@/lib/spotDiscovery';

export interface SelectedMunicipalFacilityPreviewProps {
  facility: MunicipalFacility;
  distanceMeters?: number | null;
  onClose: () => void;
  className?: string;
}

/**
 * Detail panel for a selected municipal facility — separate from community
 * {@link SelectedSpotPreview}. Read-only: no claim / edit / report actions.
 */
export function SelectedMunicipalFacilityPreview({
  facility,
  distanceMeters = null,
  onClose,
  className,
}: SelectedMunicipalFacilityPreviewProps) {
  const { t } = useTranslation('map');
  const title =
    facility.displayName?.trim() ||
    facility.addressText?.trim() ||
    t('municipal.unnamedFacility');
  const occupancyKind = municipalOccupancyPresentationKind(facility);
  const dataSourceLabels = municipalDataSourceLabels(facility);
  const dataSourceLine = formatMunicipalDataSourcesLine(facility);
  const dataSourceHeadingKey =
    dataSourceLabels.length > 1 ? 'municipal.dataSources' : 'municipal.dataSource';
  const availabilityCopyKey = municipalAvailabilityCopyKey(occupancyKind);

  return (
    <div
      role="group"
      aria-label={t('municipal.previewAria', { name: title })}
      data-testid="selected-municipal-facility-preview"
      className={cn(
        'pointer-events-auto animate-fade-in-up rounded-3xl glass-panel p-md shadow-deep ring-1 ring-secondary/15',
        className,
      )}
    >
      <div className="flex items-start gap-md">
        <span
          aria-hidden
          className="relative flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl bg-secondary text-on-secondary shadow-md"
        >
          <Icon name="garage" className="text-[28px] leading-none" filled />
        </span>

        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-sm">
            <div className="min-w-0">
              <SoftBadge tone="success" icon="garage" className="mb-xs">
                {t('municipal.inventoryLabel')}
              </SoftBadge>
              <p className="m-0 truncate text-body-md font-semibold text-on-surface" title={title}>
                {title}
              </p>
            </div>
            <IconButton
              aria-label={t('preview.closeAria')}
              icon="close"
              variant="ghost"
              onClick={onClose}
              className="-mr-2 -mt-2 h-11 w-11 shrink-0"
            />
          </div>

          <div className="mt-xs flex flex-wrap items-center gap-x-sm gap-y-xs text-label-sm text-on-surface-variant">
            {distanceMeters != null ? (
              <span className="inline-flex items-center gap-xs rounded-full bg-secondary/10 px-sm py-xs font-semibold text-secondary">
                <Icon name="near_me" className="text-[14px] leading-none" />
                {formatDistance(distanceMeters)}
              </span>
            ) : null}
            <span
              className="inline-flex items-center gap-xs rounded-full bg-surface-container px-sm py-xs font-semibold"
              data-testid="municipal-occupancy-status"
            >
              <Icon name="schedule" className="text-[14px] leading-none" />
              {t(`municipal.freshness.${municipalFreshnessCopyKey(occupancyKind)}`)}
            </span>
          </div>

          {facility.addressText && facility.displayName ? (
            <p className="m-0 mt-sm text-label-sm text-on-surface-variant">{facility.addressText}</p>
          ) : null}

          <dl className="m-0 mt-sm grid gap-xs text-label-sm text-on-surface">
            {dataSourceLine ? (
              <div className="flex gap-sm">
                <dt className="shrink-0 font-medium text-on-surface-variant">
                  {t(dataSourceHeadingKey)}
                </dt>
                <dd className="m-0 min-w-0">{dataSourceLine}</dd>
              </div>
            ) : null}
            <div className="flex gap-sm">
              <dt className="shrink-0 font-medium text-on-surface-variant">
                {t('municipal.availability')}
              </dt>
              <dd className="m-0" data-testid="municipal-availability-copy">
                {occupancyKind === 'live' || occupancyKind === 'aging'
                  ? t(`municipal.${availabilityCopyKey}`, {
                      available: facility.availableSpaces,
                      capacity: facility.capacityTotal ?? '—',
                    })
                  : occupancyKind === 'stale_live' && facility.capacityTotal != null
                    ? `${t(`municipal.${availabilityCopyKey}`)} · ${t('municipal.capacityOnly', {
                        capacity: facility.capacityTotal,
                      })}`
                    : t(`municipal.${availabilityCopyKey}`)}
              </dd>
            </div>
            {facility.operatorName ? (
              <div className="flex gap-sm">
                <dt className="shrink-0 font-medium text-on-surface-variant">
                  {t('municipal.operator')}
                </dt>
                <dd className="m-0">{facility.operatorName}</dd>
              </div>
            ) : null}
          </dl>
        </div>
      </div>

      <Link
        to={
          distanceMeters != null && Number.isFinite(distanceMeters)
            ? `/facilities/${facility.id}?d=${Math.round(distanceMeters)}`
            : `/facilities/${facility.id}`
        }
        data-testid="municipal-facility-view-details"
        className="mt-md inline-flex w-full items-center justify-center gap-xs rounded-full bg-secondary px-lg py-md text-label-md font-semibold text-on-secondary no-underline shadow-md transition-colors hover:bg-secondary-container focus:outline-none focus-visible:ring-4 focus-visible:ring-secondary/30"
      >
        <Icon name="arrow_forward" className="text-[18px] leading-none" />
        {t('municipal.viewFacilityDetails')}
      </Link>
    </div>
  );
}
