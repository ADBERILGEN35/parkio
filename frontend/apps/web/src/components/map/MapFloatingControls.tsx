import { Icon } from '@parkio/ui';
import { useMap } from 'react-map-gl/maplibre';
import { useTranslation } from 'react-i18next';

export interface MapFloatingControlsProps {
  onLocate: () => void;
  locating: boolean;
  /** Offset controls left when the results sidebar is open (desktop). */
  sidebarOpen?: boolean;
  /** When set, shows a compact recenter-on-parked-car control. */
  onFocusParkedCar?: () => void;
}

const railButtonClass =
  'flex h-11 w-11 items-center justify-center text-on-surface transition-colors hover:bg-surface-container focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30 focus-visible:ring-inset motion-safe:active:scale-95 disabled:opacity-60';

/**
 * Locate + zoom stack — DESIGN_SYSTEM §2.5 map controls.
 * Must render inside a react-map-gl `<Map>`.
 *
 * One coherent right-side rail: locate / zoom-in / zoom-out share a single
 * pill so they never read as disconnected floating widgets. Explore must pass
 * {@link sidebarOpen}=false so the rail is not shifted for a MapPage sidebar.
 */
export function MapFloatingControls({
  onLocate,
  locating,
  sidebarOpen = true,
  onFocusParkedCar,
}: MapFloatingControlsProps) {
  const { t } = useTranslation('map');
  const { current: map } = useMap();

  return (
    <div
      data-testid="map-floating-controls"
      className={`pointer-events-none absolute z-[1055] flex flex-col items-end gap-1.5 bottom-[max(5.5rem,calc(env(safe-area-inset-bottom)+4.25rem))] right-md md:bottom-md ${
        sidebarOpen ? 'md:right-[420px]' : 'md:right-md'
      }`}
    >
      {onFocusParkedCar ? (
        <button
          type="button"
          aria-label={t('parkingSession.recenterAria')}
          title={t('parkingSession.recenterAria')}
          data-testid="parked-car-recenter"
          onClick={onFocusParkedCar}
          className="pointer-events-auto flex h-11 w-11 items-center justify-center rounded-full bg-surface-container-lowest text-on-surface shadow-lg transition-all duration-std hover:bg-surface-container focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30 motion-safe:active:scale-95"
        >
          <Icon name="directions_car" className="text-[20px] leading-none text-primary" />
        </button>
      ) : null}
      <div
        data-testid="map-floating-rail"
        className="pointer-events-auto flex flex-col overflow-hidden rounded-full bg-surface-container-lowest shadow-lg"
      >
        <button
          type="button"
          aria-label={t('locateAria')}
          title={t('locateAria')}
          data-testid="map-floating-locate"
          disabled={locating}
          aria-busy={locating || undefined}
          onClick={onLocate}
          className={railButtonClass}
        >
          <Icon name="my_location" className="text-[20px] leading-none text-primary" />
        </button>
        <div className="h-px bg-outline-variant/20" aria-hidden="true" />
        <button
          type="button"
          aria-label={t('zoomIn')}
          title={t('zoomIn')}
          data-testid="map-floating-zoom-in"
          onClick={() => map?.zoomIn()}
          className={railButtonClass}
        >
          <Icon name="add" className="text-[20px] leading-none" />
        </button>
        <div className="h-px bg-outline-variant/20" aria-hidden="true" />
        <button
          type="button"
          aria-label={t('zoomOut')}
          title={t('zoomOut')}
          data-testid="map-floating-zoom-out"
          onClick={() => map?.zoomOut()}
          className={railButtonClass}
        >
          <Icon name="remove" className="text-[20px] leading-none" />
        </button>
      </div>
    </div>
  );
}
