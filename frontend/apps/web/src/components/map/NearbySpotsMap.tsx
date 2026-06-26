import './maplibreSetup';
import type { PublicSpot } from '@parkio/types';
import { cn, getSpotStatusVisual, getTrustFreshnessVisual } from '@parkio/ui';
import { memo, useCallback, useMemo } from 'react';
import Map, { Marker } from 'react-map-gl/maplibre';
import { MapFloatingControls } from './MapFloatingControls';
import { DEFAULT_MAP_ZOOM, getMapStyle, type LatLng } from './mapConfig';
import { Recenter } from './Recenter';

export interface NearbySpotsMapProps {
  center: LatLng;
  zoom?: number;
  spots: PublicSpot[];
  onPickCenter: (lat: number, lng: number) => void;
  /** Currently selected spot id (controlled — the preview card lives outside). */
  selectedId?: string | null;
  /** Selection changes (marker tap, or `null` when the map background is tapped). */
  onSelectSpot?: (id: string | null) => void;
  height?: number | string;
  onLocate?: () => void;
  locating?: boolean;
  showFloatingControls?: boolean;
}

/** Premium, status-aware marker shown for each real spot. */
const SpotMarker = memo(function SpotMarker({
  spot,
  selected,
  onSelect,
}: {
  spot: PublicSpot;
  selected: boolean;
  /** Stable across renders so `memo` skips unaffected markers on selection. */
  onSelect: (id: string) => void;
}) {
  const status = getSpotStatusVisual(spot.status);
  const { freshness } = getTrustFreshnessVisual(spot.updatedAt);
  const dimmed = freshness === 'aging' || freshness === 'stale';
  const label = `${status.label} parking spot${spot.addressText ? ` near ${spot.addressText}` : ''}`;

  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={selected}
      onClick={(event) => {
        event.stopPropagation();
        onSelect(spot.id);
      }}
      className={cn(
        'group relative flex h-10 w-10 items-center justify-center rounded-full border-2 border-white bg-surface-container-lowest shadow-lg transition-all duration-std focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30 motion-safe:hover:-translate-y-0.5',
        selected && 'scale-110 shadow-xl ring-4 ring-primary/20',
        dimmed && !selected && 'opacity-75',
      )}
    >
      {selected ? (
        <span className={cn('absolute inset-0 rounded-full opacity-30 motion-safe:animate-ping', status.dotClassName)} />
      ) : null}
      <span
        className={cn(
          'relative flex h-6 w-6 items-center justify-center rounded-full text-[12px] font-black text-white shadow-sm transition-transform duration-std group-hover:scale-105',
          status.dotClassName,
        )}
      >
        P
      </span>
      <span className="sr-only">{freshness === 'fresh' ? 'Recently updated' : `Freshness ${freshness}`}</span>
    </button>
  );
});

export function NearbySpotsMap({
  center,
  zoom = DEFAULT_MAP_ZOOM,
  spots,
  onPickCenter,
  selectedId = null,
  onSelectSpot,
  height = 320,
  onLocate,
  locating = false,
  showFloatingControls = false,
}: NearbySpotsMapProps) {
  // Stable selection handler: passed by reference to every marker so the memoized
  // `SpotMarker` only re-renders when *its own* `selected` flag flips. Selecting a
  // spot therefore re-renders two markers (the old + new selection), not all N.
  const handleSelect = useCallback((id: string) => onSelectSpot?.(id), [onSelectSpot]);

  // Marker geometry/labels change only with the spot set; `selected` styling is a
  // cheap per-marker prop. Panning/dragging never rebuilds this list.
  const markers = useMemo(
    () =>
      spots.map((spot) => (
        <Marker key={spot.id} longitude={spot.longitude} latitude={spot.latitude} anchor="center">
          <SpotMarker spot={spot} selected={selectedId === spot.id} onSelect={handleSelect} />
        </Marker>
      )),
    [spots, selectedId, handleSelect],
  );

  return (
    <Map
      initialViewState={{ longitude: center.lng, latitude: center.lat, zoom }}
      mapStyle={getMapStyle()}
      dragRotate={false}
      pitchWithRotate={false}
      onClick={(event) => {
        onSelectSpot?.(null);
        onPickCenter(event.lngLat.lat, event.lngLat.lng);
      }}
      style={{ height, width: '100%' }}
    >
      <Recenter lat={center.lat} lng={center.lng} zoom={zoom} />
      {showFloatingControls && onLocate ? (
        <MapFloatingControls onLocate={onLocate} locating={locating} sidebarOpen />
      ) : null}

      {/* Current search center indicator. */}
      <Marker longitude={center.lng} latitude={center.lat} anchor="center">
        <span className="pointer-events-none block h-4 w-4 rounded-full border-2 border-white bg-primary/50 shadow-md" />
      </Marker>

      {markers}
    </Map>
  );
}
