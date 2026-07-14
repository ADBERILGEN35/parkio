import { Icon } from '@parkio/ui';
import { useMap } from 'react-map-gl/maplibre';
import { useTranslation } from 'react-i18next';

export interface MapFloatingControlsProps {
  onLocate: () => void;
  locating: boolean;
  /** Offset controls left when the results sidebar is open (desktop). */
  sidebarOpen?: boolean;
}

/**
 * Locate + zoom stack — DESIGN_SYSTEM §2.5 map controls.
 * Must render inside a react-map-gl `<Map>`.
 */
export function MapFloatingControls({
  onLocate,
  locating,
  sidebarOpen = true,
}: MapFloatingControlsProps) {
  const { t } = useTranslation('map');
  const { current: map } = useMap();

  return (
    <div
      className={`pointer-events-none absolute z-[1055] flex flex-col gap-sm bottom-[7rem] right-md md:bottom-md ${
        sidebarOpen ? 'md:right-[420px]' : 'md:right-md'
      }`}
    >
      <button
        type="button"
        aria-label={t('locateAria')}
        disabled={locating}
        onClick={onLocate}
        className="pointer-events-auto flex h-11 w-11 items-center justify-center rounded-full bg-surface-container-lowest text-on-surface shadow-lg transition-all duration-std hover:bg-surface-container motion-safe:active:scale-95 disabled:opacity-60"
      >
        <Icon name="my_location" className="text-[20px] leading-none text-primary" />
      </button>
      <div className="pointer-events-auto flex flex-col overflow-hidden rounded-full bg-surface-container-lowest shadow-lg">
        <button
          type="button"
          aria-label={t('zoomIn')}
          onClick={() => map?.zoomIn()}
          className="flex h-11 w-11 items-center justify-center text-on-surface transition-colors hover:bg-surface-container motion-safe:active:scale-95"
        >
          <Icon name="add" className="text-[20px] leading-none" />
        </button>
        <div className="h-px bg-outline-variant/20" />
        <button
          type="button"
          aria-label={t('zoomOut')}
          onClick={() => map?.zoomOut()}
          className="flex h-11 w-11 items-center justify-center text-on-surface transition-colors hover:bg-surface-container motion-safe:active:scale-95"
        >
          <Icon name="remove" className="text-[20px] leading-none" />
        </button>
      </div>
    </div>
  );
}
