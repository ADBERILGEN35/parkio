import { Icon, cn } from '@parkio/ui';
import { memo } from 'react';
import { useTranslation } from 'react-i18next';

export interface ParkedCarMarkerProps {
  selected: boolean;
  onSelect: () => void;
}

/**
 * Dedicated parked-car pin — visually distinct from community "P" spot markers.
 * Click focuses the active Parking Session experience; never opens Spot Detail.
 */
export const ParkedCarMarker = memo(function ParkedCarMarker({
  selected,
  onSelect,
}: ParkedCarMarkerProps) {
  const { t } = useTranslation('map');
  const label = t('parkingSession.markerAria');

  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={selected}
      data-testid="parked-car-marker"
      onClick={(event) => {
        event.stopPropagation();
        onSelect();
      }}
      className={cn(
        'group relative flex h-11 w-11 items-center justify-center rounded-full border-2 border-white bg-primary text-on-primary shadow-lg transition-transform duration-std motion-reduce:transition-none focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30 motion-safe:hover:-translate-y-0.5',
        selected && 'scale-110 shadow-xl ring-4 ring-primary/30',
      )}
    >
      <Icon name="directions_car" className="relative text-[22px] leading-none" filled />
    </button>
  );
});