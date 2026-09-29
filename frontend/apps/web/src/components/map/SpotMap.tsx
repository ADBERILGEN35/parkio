import './maplibreSetup';
import { Icon, cn } from '@parkio/ui';
import Map, { Marker } from 'react-map-gl/maplibre';
import { DETAIL_ZOOM, getMapStyle } from './mapConfig';

export interface SpotMapProps {
  latitude: number;
  longitude: number;
  height?: number;
  /** Accessible name for the map region (and marker when custom). */
  ariaLabel?: string;
  /**
   * Marker presentation. `municipal` reuses inventory visual language (garage pin)
   * without forking the map stack; default keeps the community SpotMap pin.
   */
  markerPresentation?: 'default' | 'municipal';
  /** Called when MapLibre reports a map error (tiles/style). Does not retry. */
  onError?: () => void;
}

/** Read-only map centered on a single spot, with one marker. */
export function SpotMap({
  latitude,
  longitude,
  height = 220,
  ariaLabel,
  markerPresentation = 'default',
  onError,
}: SpotMapProps) {
  return (
    <div
      role="region"
      aria-label={ariaLabel}
      data-testid="spot-map"
      style={{ height, width: '100%' }}
    >
      <Map
        initialViewState={{ longitude, latitude, zoom: DETAIL_ZOOM }}
        mapStyle={getMapStyle()}
        scrollZoom={false}
        dragRotate={false}
        pitchWithRotate={false}
        style={{ height: '100%', width: '100%', borderRadius: '0.5rem' }}
        onError={() => {
          onError?.();
        }}
      >
        {markerPresentation === 'municipal' ? (
          <Marker longitude={longitude} latitude={latitude} anchor="bottom">
            <div
              role="img"
              aria-label={ariaLabel}
              data-testid="spot-map-municipal-marker"
              className={cn(
                'pointer-events-none flex h-10 w-10 items-center justify-center',
                'rounded-xl border-2 border-white bg-surface-container-lowest shadow-lg',
              )}
            >
              <span className="flex h-6 w-6 items-center justify-center rounded-md bg-secondary text-on-secondary shadow-sm">
                <Icon name="local_parking" className="text-[14px] leading-none" filled />
              </span>
            </div>
          </Marker>
        ) : (
          <Marker longitude={longitude} latitude={latitude} anchor="bottom" />
        )}
      </Map>
    </div>
  );
}
