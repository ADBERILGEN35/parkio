import type { NearbySearchParams, ParkingStatus, PublicSpot, VehicleType } from '@parkio/types';
import { EmptyState, Icon, SpotCardSkeleton, cn, getSpotStatusVisual } from '@parkio/ui';
import { spotStatusLabel } from '@/lib/localized-status';
import type { UseQueryResult } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { SpotResultCard } from '@/components/product/SpotResultCard';
import {
  hasActiveFilters,
  type SpotFilters,
  type SpotSort,
  type SpotWithDistance,
} from '@/lib/spotDiscovery';

export interface DiscoveryResultsProps {
  search: UseQueryResult<PublicSpot[], Error>;
  params: NearbySearchParams | null;
  /** Already filtered + sorted spots, ready to render. */
  spots: SpotWithDistance[];
  /** Result count before presentation filters (for the "x of y" label). */
  totalCount: number;
  filters: SpotFilters;
  onFiltersChange: (filters: SpotFilters) => void;
  /** Statuses present in the unfiltered result set, in canonical order. */
  availableStatuses: ParkingStatus[];
  sort: SpotSort;
  onSortChange: (sort: SpotSort) => void;
  sortOptions: SpotSort[];
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  selectionFromMap?: boolean;
  userVehicleType?: VehicleType | null;
  /**
   * When true, community fetched-empty copy is subordinate to a healthy sibling
   * inventory (WEB-MUNI-06). Hidden inventories must not set this.
   */
  siblingInventoryHasResults?: boolean;
}

/**
 * Discovery panel: result count, sort control, presentation filter chips, and
 * the spot result list. Reused identically by the desktop sidebar and the mobile
 * bottom sheet so behavior stays in one place.
 */
export function DiscoveryResults({
  search,
  params,
  spots,
  totalCount,
  filters,
  onFiltersChange,
  availableStatuses,
  sort,
  onSortChange,
  sortOptions,
  selectedId,
  onSelect,
  selectionFromMap = false,
  userVehicleType = null,
  siblingInventoryHasResults = false,
}: DiscoveryResultsProps) {
  const { t } = useTranslation('map');

  if (params === null) {
    return (
      <EmptyState
        icon="travel_explore"
        title={t('discovery.idleTitle')}
        description={t('discovery.idleDescription')}
      />
    );
  }

  if (search.isPending) {
    return (
      <>
        <div>
          <h2 className="m-0 text-title-lg text-on-surface">{t('discovery.searchingTitle')}</h2>
          <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
            {t('discovery.searchingDescription')}
          </p>
        </div>
        <div className="flex flex-col gap-sm" role="status" aria-label={t('discovery.searchingAria')}>
          <SpotCardSkeleton />
          <SpotCardSkeleton />
          <SpotCardSkeleton />
        </div>
      </>
    );
  }

  if (search.isError) {
    return <FriendlyApiErrorMessage error={search.error} />;
  }

  if (totalCount === 0) {
    return (
      <EmptyState
        icon="location_off"
        title={
          siblingInventoryHasResults
            ? t('discovery.emptyWithMunicipalTitle')
            : t('discovery.emptyTitle')
        }
        description={
          siblingInventoryHasResults
            ? t('discovery.emptyWithMunicipalDescription')
            : t('discovery.emptyDescription')
        }
      />
    );
  }

  const filtersActive = hasActiveFilters(filters);

  return (
    <>
      <div className="flex items-center justify-between gap-sm">
        <h2 className="m-0 text-title-lg text-on-surface">
          {filtersActive
            ? t('discovery.resultsOf', { visible: spots.length, total: totalCount })
            : t('discovery.resultsCount', { count: totalCount })}
        </h2>
        <SortControl sort={sort} onSortChange={onSortChange} options={sortOptions} />
      </div>

      <FilterBar
        filters={filters}
        onFiltersChange={onFiltersChange}
        availableStatuses={availableStatuses}
      />

      {spots.length === 0 ? (
        <EmptyState
          icon="filter_alt_off"
          title={t('discovery.noMatchTitle')}
          description={t('discovery.noMatchDescription')}
        />
      ) : (
        <ul className="m-0 flex list-none flex-col gap-sm p-0">
          {spots.map((spot) => (
            <SpotListItem
              key={spot.id}
              spot={spot}
              userVehicleType={userVehicleType}
              selected={spot.id === selectedId}
              selectionFromMap={selectionFromMap}
              onSelect={() => onSelect(spot.id)}
            />
          ))}
        </ul>
      )}
    </>
  );
}

function SpotListItem({
  spot,
  userVehicleType,
  selected,
  selectionFromMap,
  onSelect,
}: {
  spot: SpotWithDistance;
  userVehicleType: VehicleType | null;
  selected: boolean;
  selectionFromMap: boolean;
  onSelect: () => void;
}) {
  const ref = useRef<HTMLLIElement>(null);

  // Keep the selected card in view when selection is driven from the map marker.
  // `scrollIntoView` is not implemented in jsdom, hence the optional call.
  useEffect(() => {
    if (selected && selectionFromMap) {
      ref.current?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
    }
  }, [selected, selectionFromMap]);

  return (
    <li ref={ref}>
      <SpotResultCard
        spot={spot}
        userVehicleType={userVehicleType}
        distanceMeters={spot.distanceMeters}
        selected={selected}
        onSelect={onSelect}
      />
    </li>
  );
}

function SortControl({
  sort,
  onSortChange,
  options,
}: {
  sort: SpotSort;
  onSortChange: (sort: SpotSort) => void;
  options: SpotSort[];
}) {
  const { t } = useTranslation('map');
  const labels: Record<SpotSort, string> = {
    nearest: t('discovery.sortNearest'),
    newest: t('discovery.sortNewest'),
    recent: t('discovery.sortRecent'),
  };
  return (
    <label className="flex shrink-0 items-center gap-xs text-label-sm text-on-surface-variant">
      <Icon name="sort" className="text-[16px] leading-none" />
      <span className="sr-only">{t('discovery.sortLabel')}</span>
      <select
        aria-label={t('discovery.sortLabel')}
        value={sort}
        onChange={(event) => onSortChange(event.target.value as SpotSort)}
        className="rounded-full border border-outline-variant/40 bg-surface-container-lowest px-sm py-xs text-label-sm font-medium text-on-surface focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        {options.map((option) => (
          <option key={option} value={option}>
            {labels[option]}
          </option>
        ))}
      </select>
    </label>
  );
}

function FilterBar({
  filters,
  onFiltersChange,
  availableStatuses,
}: {
  filters: SpotFilters;
  onFiltersChange: (filters: SpotFilters) => void;
  availableStatuses: ParkingStatus[];
}) {
  const { t } = useTranslation('map');

  const toggleStatus = (status: ParkingStatus) => {
    const next = filters.statuses.includes(status)
      ? filters.statuses.filter((value) => value !== status)
      : [...filters.statuses, status];
    onFiltersChange({ ...filters, statuses: next });
  };

  const active = hasActiveFilters(filters);

  return (
    <div
      role="group"
      aria-label={t('discovery.filterResultsAria')}
      className="-mx-md flex items-center gap-xs overflow-x-auto px-md pb-xs hide-scrollbar"
    >
      {availableStatuses.map((status) => {
        const visual = getSpotStatusVisual(status);
        const on = filters.statuses.includes(status);
        return (
          <FilterChip key={status} pressed={on} onClick={() => toggleStatus(status)}>
            <span className={cn('h-2 w-2 shrink-0 rounded-full', visual.dotClassName)} aria-hidden />
            {spotStatusLabel(status, t)}
          </FilterChip>
        );
      })}

      <FilterChip
        pressed={filters.legalOnly}
        onClick={() => onFiltersChange({ ...filters, legalOnly: !filters.legalOnly })}
      >
        <Icon name="gavel" className="text-[14px] leading-none" />
        {t('discovery.legalOnly')}
      </FilterChip>

      {active ? (
        <button
          type="button"
          onClick={() => onFiltersChange({ statuses: [], legalOnly: false })}
          className="ml-auto inline-flex shrink-0 items-center gap-0.5 whitespace-nowrap rounded-full px-sm py-xs text-label-sm font-semibold text-primary hover:bg-primary/10 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          <Icon name="close" className="text-[14px] leading-none" />
          {t('discovery.clear')}
        </button>
      ) : null}
    </div>
  );
}

function FilterChip({
  pressed,
  onClick,
  children,
}: {
  pressed: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onClick}
      className={cn(
        'inline-flex shrink-0 items-center gap-xs whitespace-nowrap rounded-full border px-sm py-xs text-label-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-primary',
        pressed
          ? 'border-primary bg-primary/10 text-primary'
          : 'border-outline-variant/40 bg-surface-container-lowest text-on-surface-variant hover:bg-surface-container',
      )}
    >
      {children}
    </button>
  );
}
