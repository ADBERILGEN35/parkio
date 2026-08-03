import type { MunicipalFacility, MunicipalFacilityType, NearbySearchParams } from '@parkio/types';
import { EmptyState, Icon, SpotCardSkeleton, cn } from '@parkio/ui';
import type { UseQueryResult } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import {
  EMPTY_MUNICIPAL_FILTERS,
  MUNICIPAL_AVAILABILITY_FILTERS,
  formatDistance,
  hasActiveMunicipalFilters,
  haversineMeters,
  type MunicipalAvailabilityFilter,
  type MunicipalFacilityFilters,
} from '@/lib/spotDiscovery';

export interface MunicipalFacilityResultsProps {
  search: UseQueryResult<MunicipalFacility[], Error>;
  params: NearbySearchParams | null;
  /** Already filtered facilities ready to render. */
  facilities: MunicipalFacility[];
  /** Count before presentation filters (for "x of y"). */
  totalCount: number;
  filters: MunicipalFacilityFilters;
  onFiltersChange: (filters: MunicipalFacilityFilters) => void;
  /** Exact sourceLabel values present in the unfiltered set. */
  availableSourceLabels: string[];
  /** Facility types present in the unfiltered set. */
  availableFacilityTypes: MunicipalFacilityType[];
  selectedId: string | null;
  onSelect: (id: string | null) => void;
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

/**
 * Separate discovery list for municipal facilities (WEB-MUNI-01 / WEB-MUNI-03).
 * Never fused with community {@link DiscoveryResults}.
 */
export function MunicipalFacilityResults({
  search,
  params,
  facilities,
  totalCount,
  filters,
  onFiltersChange,
  availableSourceLabels,
  availableFacilityTypes,
  selectedId,
  onSelect,
}: MunicipalFacilityResultsProps) {
  const { t } = useTranslation('map');

  if (params === null) {
    return null;
  }

  const filtersActive = hasActiveMunicipalFilters(filters);

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

      {search.isSuccess && totalCount === 0 ? (
        <div data-testid="municipal-facility-empty">
          <EmptyState
            icon="garage"
            title={t('municipal.emptyTitle')}
            description={t('municipal.emptyDescription')}
          />
        </div>
      ) : null}

      {search.isSuccess && totalCount > 0 ? (
        <>
          <p
            className="m-0 text-label-sm font-semibold text-on-surface"
            data-testid="municipal-facility-count"
            aria-live="polite"
          >
            {filtersActive
              ? t('municipal.resultsOf', { visible: facilities.length, total: totalCount })
              : t('municipal.resultsCount', { count: totalCount })}
          </p>

          <MunicipalFilterBar
            filters={filters}
            onFiltersChange={onFiltersChange}
            availableSourceLabels={availableSourceLabels}
            availableFacilityTypes={availableFacilityTypes}
          />

          <span className="sr-only" role="status" aria-live="polite">
            {t('municipal.filterStateAria', { count: facilities.length })}
          </span>

          {facilities.length === 0 ? (
            <div data-testid="municipal-facility-filtered-empty">
              <EmptyState
                icon="filter_alt_off"
                title={t('municipal.filteredEmptyTitle')}
                description={t('municipal.filteredEmptyDescription')}
              />
            </div>
          ) : (
            <ul
              className="m-0 flex list-none flex-col gap-sm p-0"
              data-testid="municipal-facility-list"
            >
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
                          <span className="font-semibold text-secondary">
                            {formatDistance(distance)}
                          </span>
                          {facility.sourceLabel ? <span>· {facility.sourceLabel}</span> : null}
                        </span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </>
      ) : null}
    </section>
  );
}

function MunicipalFilterBar({
  filters,
  onFiltersChange,
  availableSourceLabels,
  availableFacilityTypes,
}: {
  filters: MunicipalFacilityFilters;
  onFiltersChange: (filters: MunicipalFacilityFilters) => void;
  availableSourceLabels: string[];
  availableFacilityTypes: MunicipalFacilityType[];
}) {
  const { t } = useTranslation('map');
  const active = hasActiveMunicipalFilters(filters);

  const setAvailability = (availability: MunicipalAvailabilityFilter) => {
    onFiltersChange({ ...filters, availability });
  };

  const toggleSource = (label: string) => {
    const next = filters.sourceLabels.includes(label)
      ? filters.sourceLabels.filter((value) => value !== label)
      : [...filters.sourceLabels, label];
    onFiltersChange({ ...filters, sourceLabels: next });
  };

  const toggleType = (type: MunicipalFacilityType) => {
    const next = filters.facilityTypes.includes(type)
      ? filters.facilityTypes.filter((value) => value !== type)
      : [...filters.facilityTypes, type];
    onFiltersChange({ ...filters, facilityTypes: next });
  };

  return (
    <div
      role="group"
      aria-label={t('municipal.filterResultsAria')}
      data-testid="municipal-facility-filters"
      className="-mx-md flex items-center gap-xs overflow-x-auto px-md pb-xs hide-scrollbar"
    >
      {MUNICIPAL_AVAILABILITY_FILTERS.filter((value) => value !== 'all').map((value) => {
        const pressed = filters.availability === value;
        return (
          <FilterChip
            key={value}
            pressed={pressed}
            onClick={() => setAvailability(pressed ? 'all' : value)}
            testId={`municipal-filter-availability-${value}`}
          >
            {t(`municipal.availabilityFilter.${value}`)}
          </FilterChip>
        );
      })}

      {availableSourceLabels.map((label) => {
        const pressed = filters.sourceLabels.includes(label);
        return (
          <FilterChip
            key={label}
            pressed={pressed}
            onClick={() => toggleSource(label)}
            testId="municipal-filter-source"
            title={label}
          >
            <span className="max-w-[10rem] truncate">{label}</span>
          </FilterChip>
        );
      })}

      {availableFacilityTypes.map((type) => {
        const pressed = filters.facilityTypes.includes(type);
        return (
          <FilterChip
            key={type}
            pressed={pressed}
            onClick={() => toggleType(type)}
            testId={`municipal-filter-type-${type}`}
          >
            {t(facilityTypeLabelKey(type))}
          </FilterChip>
        );
      })}

      <FilterChip
        pressed={filters.provenanceOnly}
        onClick={() => onFiltersChange({ ...filters, provenanceOnly: !filters.provenanceOnly })}
        testId="municipal-filter-provenance"
      >
        <Icon name="info" className="text-[14px] leading-none" />
        {t('municipal.provenanceOnly')}
      </FilterChip>

      {active ? (
        <button
          type="button"
          data-testid="municipal-filter-clear"
          onClick={() => onFiltersChange(EMPTY_MUNICIPAL_FILTERS)}
          className="ml-auto inline-flex shrink-0 items-center gap-0.5 whitespace-nowrap rounded-full px-sm py-xs text-label-sm font-semibold text-secondary hover:bg-secondary/10 focus:outline-none focus-visible:ring-2 focus-visible:ring-secondary"
        >
          <Icon name="close" className="text-[14px] leading-none" />
          {t('municipal.clearFilters')}
        </button>
      ) : null}
    </div>
  );
}

function FilterChip({
  pressed,
  onClick,
  children,
  testId,
  title,
}: {
  pressed: boolean;
  onClick: () => void;
  children: React.ReactNode;
  testId?: string;
  title?: string;
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      title={title}
      data-testid={testId}
      onClick={onClick}
      className={cn(
        'inline-flex shrink-0 items-center gap-xs whitespace-nowrap rounded-full border px-sm py-xs text-label-sm font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-secondary',
        pressed
          ? 'border-secondary bg-secondary/10 text-secondary'
          : 'border-outline-variant/40 bg-surface-container-lowest text-on-surface-variant hover:bg-surface-container',
      )}
    >
      {children}
    </button>
  );
}
