import type { MunicipalFacility, MunicipalOccupancyFreshness } from '@parkio/types';
import { Icon, IconButton, SoftBadge, cn } from '@parkio/ui';
import { useTranslation } from 'react-i18next';
import { formatDistance } from '@/lib/spotDiscovery';

export interface SelectedMunicipalFacilityPreviewProps {
  facility: MunicipalFacility;
  distanceMeters?: number | null;
  onClose: () => void;
  className?: string;
}

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
  const freshness = facility.availabilityFreshness ?? facility.freshness;
  const provenanceEntries = Object.entries(facility.selectedFieldProvenanceSummary ?? {}).slice(
    0,
    6,
  );

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
            <span className="inline-flex items-center gap-xs rounded-full bg-surface-container px-sm py-xs font-semibold">
              <Icon name="schedule" className="text-[14px] leading-none" />
              {t(freshnessLabelKey(freshness))}
            </span>
          </div>

          {facility.addressText && facility.displayName ? (
            <p className="m-0 mt-sm text-label-sm text-on-surface-variant">{facility.addressText}</p>
          ) : null}

          <dl className="m-0 mt-sm grid gap-xs text-label-sm text-on-surface">
            {facility.sourceLabel || facility.attribution ? (
              <div className="flex gap-sm">
                <dt className="shrink-0 font-medium text-on-surface-variant">
                  {t('municipal.source')}
                </dt>
                <dd className="m-0 min-w-0">
                  {facility.sourceLabel ?? facility.attribution}
                  {facility.attribution && facility.sourceLabel
                    ? ` · ${facility.attribution}`
                    : null}
                </dd>
              </div>
            ) : null}
            {facility.availableSpaces != null || facility.capacityTotal != null ? (
              <div className="flex gap-sm">
                <dt className="shrink-0 font-medium text-on-surface-variant">
                  {t('municipal.availability')}
                </dt>
                <dd className="m-0">
                  {facility.availableSpaces != null
                    ? t('municipal.spacesAvailable', {
                        available: facility.availableSpaces,
                        capacity: facility.capacityTotal ?? '—',
                      })
                    : t('municipal.capacityOnly', { capacity: facility.capacityTotal })}
                </dd>
              </div>
            ) : (
              <div className="flex gap-sm">
                <dt className="shrink-0 font-medium text-on-surface-variant">
                  {t('municipal.availability')}
                </dt>
                <dd className="m-0">{t('municipal.availabilityUnknown')}</dd>
              </div>
            )}
            {facility.operatorName ? (
              <div className="flex gap-sm">
                <dt className="shrink-0 font-medium text-on-surface-variant">
                  {t('municipal.operator')}
                </dt>
                <dd className="m-0">{facility.operatorName}</dd>
              </div>
            ) : null}
          </dl>

          {provenanceEntries.length > 0 ? (
            <div className="mt-sm rounded-2xl bg-surface-container/80 px-sm py-sm">
              <p className="m-0 text-label-sm font-semibold text-on-surface-variant">
                {t('municipal.provenance')}
              </p>
              <ul className="m-0 mt-xs list-none space-y-xs p-0 text-label-sm text-on-surface">
                {provenanceEntries.map(([field, source]) => (
                  <li key={field} className="flex justify-between gap-sm">
                    <span className="text-on-surface-variant">{field}</span>
                    <span className="truncate font-medium">{source}</span>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
