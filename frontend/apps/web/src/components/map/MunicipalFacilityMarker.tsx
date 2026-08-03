import type { MunicipalFacility, MunicipalOccupancyFreshness } from '@parkio/types';
import { cn, Icon } from '@parkio/ui';
import { memo } from 'react';
import { useTranslation } from 'react-i18next';

export interface MunicipalFacilityMarkerProps {
  facility: MunicipalFacility;
  selected: boolean;
  onSelect: (id: string) => void;
}

function freshnessTone(freshness: MunicipalOccupancyFreshness | null | undefined): string {
  switch (freshness) {
    case 'LIVE':
      return 'ring-secondary/40';
    case 'AGING':
      return 'ring-tertiary/40';
    case 'STALE':
    case 'INVALID':
      return 'opacity-75';
    default:
      return '';
  }
}

/**
 * Map pin for a municipal parking facility — visually distinct from community
 * spot markers (rounded square + garage glyph + secondary green, not circular "P").
 */
export const MunicipalFacilityMarker = memo(function MunicipalFacilityMarker({
  facility,
  selected,
  onSelect,
}: MunicipalFacilityMarkerProps) {
  const { t } = useTranslation('map');
  const title =
    facility.displayName?.trim() ||
    facility.addressText?.trim() ||
    t('municipal.unnamedFacility');
  const label = t('municipal.markerAria', { name: title });

  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={selected}
      data-testid="municipal-facility-marker"
      data-facility-id={facility.id}
      onClick={(event) => {
        event.stopPropagation();
        onSelect(facility.id);
      }}
      className={cn(
        'group relative flex h-10 w-10 items-center justify-center rounded-xl border-2 border-white bg-surface-container-lowest shadow-lg transition-all duration-std focus:outline-none focus-visible:ring-4 focus-visible:ring-secondary/30 motion-safe:hover:-translate-y-0.5',
        selected && 'scale-110 shadow-xl ring-4 ring-secondary/25',
        freshnessTone(facility.freshness ?? facility.availabilityFreshness),
      )}
    >
      {selected ? (
        <span className="absolute inset-0 rounded-xl bg-secondary opacity-30 motion-safe:animate-ping" />
      ) : null}
      <span className="relative flex h-6 w-6 items-center justify-center rounded-md bg-secondary text-on-secondary shadow-sm transition-transform duration-std group-hover:scale-105">
        <Icon name="local_parking" className="text-[14px] leading-none" filled />
      </span>
      <span className="sr-only">{t('municipal.inventoryLabel')}</span>
    </button>
  );
});
