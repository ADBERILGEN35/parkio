/**
 * Discovery helpers for the `/map` results experience.
 *
 * The pure, backend-faithful calculations (haversine distance, distance/relative
 * time formatting, distance enrichment, presentation filtering and sorting) now
 * live in the framework-agnostic {@link @parkio/geo} package so web and mobile
 * share one implementation — there is no forked business logic. This module
 * re-exports them unchanged to keep existing web import paths stable.
 */
export {
  type SpotWithDistance,
  type SpotSort,
  type SpotFilters,
  SPOT_SORT_LABELS,
  EMPTY_FILTERS,
  hasActiveFilters,
  haversineMeters,
  formatDistance,
  withDistance,
  filterSpots,
  sortSpots,
  availableSorts,
  defaultSort,
  availableStatuses,
} from '@parkio/geo';
