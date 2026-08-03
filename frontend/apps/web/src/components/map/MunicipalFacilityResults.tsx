import type { MunicipalFacility, NearbySearchParams } from '@parkio/types';
import { EmptyState, Icon, SpotCardSkeleton, cn } from '@parkio/ui';
import type { UseQueryResult } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { formatDistance, haversineMeters } from '@/lib/spotDiscovery';

export interface MunicipalFacilityResultsProps {
  search: UseQueryResult<MunicipalFacility[], Error>;
  params: NearbySearchParams | null;
  facilities: MunicipalFacility[];
  selectedId: string | null;
  onSelect: (id: string | null) => void;
}

/**
 * Separate discovery list for municipal facilities (WEB-MUNI-01).
 * Never fused with community {@link DiscoveryResults}.
 */
export function MunicipalFacilityResults({
  search,
  params,
  facilities,
  selectedId,
  onSelect,
}: MunicipalFacilityResultsProps) {
  const { t } = useTranslation('map');

  if (params === null) {
    return null;
  }

  return (
    <section
      aria-label={t('municipal.sectionAria')}
      data-testid="municipal-facility-results"
      className="flex flex-col gap-sm border-b border-outline-variant/30 pb-md"
    >
      <div className="flex items-center gap-sm">
        <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-secondary/15 text-secondary">
          <Icon name="garage" className="text-[18px] leading-none" filled />
        </span>
        <div className="min-w-0">
          <h2 className="m-0 text-title-md text-on-surface">{t('municipal.sectionTitle')}</h2>
          <p className="m-0 text-label-sm text-on-surface-variant">{t('municipal.sectionSubtitle')}</p>
        </div>
      </div>

      {search.isPending ? (
        <div
          className="flex flex-col gap-sm"
          role="status"
          aria-label={t('municipal.searchingAria')}
          data-testid="municipal-facility-loading"
        >
          <SpotCardSkeleton />
          <SpotCardSkeleton />
        </div>
      ) : null}

      {search.isError ? (
        <div data-testid="municipal-facility-error">
          <FriendlyApiErrorMessage error={search.error} />
        </div>
      ) : null}

      {search.isSuccess && facilities.length === 0 ? (
        <div data-testid="municipal-facility-empty">
          <EmptyState
            icon="garage"
            title={t('municipal.emptyTitle')}
            description={t('municipal.emptyDescription')}
          />
        </div>
      ) : null}

      {search.isSuccess && facilities.length > 0 ? (
        <ul className="m-0 flex list-none flex-col gap-sm p-0" data-testid="municipal-facility-list">
          {facilities.map((facility) => {
            const selected = facility.id === selectedId;
            const title =
              facility.displayName?.trim() ||
              facility.addressText?.trim() ||
              t('municipal.unnamedFacility');
            const distance = haversineMeters(
              { lat: params.lat, lng: params.lng },
              { lat: facility.latitude, lng: facility.longitude },
            );
            return (
              <li key={facility.id}>
                <button
                  type="button"
                  data-testid="municipal-facility-result"
                  aria-pressed={selected}
                  onClick={() => onSelect(selected ? null : facility.id)}
                  className={cn(
                    'flex w-full items-start gap-sm rounded-2xl border border-transparent bg-surface-container/60 px-md py-sm text-left transition-colors hover:bg-surface-container-high focus:outline-none focus-visible:ring-2 focus-visible:ring-secondary',
                    selected && 'border-secondary/40 bg-secondary/10 ring-1 ring-secondary/20',
                  )}
                >
                  <span className="mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-secondary text-on-secondary">
                    <Icon name="local_parking" className="text-[18px] leading-none" filled />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-label-md font-semibold text-on-surface">
                      {title}
                    </span>
                    <span className="mt-xs flex flex-wrap items-center gap-xs text-label-sm text-on-surface-variant">
                      <span className="font-semibold text-secondary">{formatDistance(distance)}</span>
                      {facility.sourceLabel ? <span>· {facility.sourceLabel}</span> : null}
                    </span>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      ) : null}
    </section>
  );
}
