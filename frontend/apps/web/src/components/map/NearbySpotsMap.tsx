import './maplibreSetup';
import type { PublicSpot } from '@parkio/types';
import { StatusBadge, cn, getSpotStatusVisual, getTrustFreshnessVisual } from '@parkio/ui';
import { useState } from 'react';
import Map, { Marker, Popup } from 'react-map-gl/maplibre';
import { Link } from 'react-router-dom';
import { MapFloatingControls } from './MapFloatingControls';
import { formatInstant, humanizeEnum } from '@/lib/format';
import { DEFAULT_MAP_ZOOM, getMapStyle, type LatLng } from './mapConfig';
import { Recenter } from './Recenter';

export interface NearbySpotsMapProps {
  center: LatLng;
  zoom?: number;
  spots: PublicSpot[];
  onPickCenter: (lat: number, lng: number) => void;
  height?: number | string;
  onLocate?: () => void;
  locating?: boolean;
  showFloatingControls?: boolean;
}

/** Premium, status-aware marker shown for each real spot. */
function SpotMarker({
  spot,
  selected,
  onSelect,
}: {
  spot: PublicSpot;
  selected: boolean;
  onSelect: () => void;
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
        onSelect();
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
}

function SpotPopup({ spot }: { spot: PublicSpot }) {
  const freshness = getTrustFreshnessVisual(spot.updatedAt);
  const address = spot.addressText ?? `${spot.latitude.toFixed(5)}, ${spot.longitude.toFixed(5)}`;

  return (
    <div className="flex min-w-[220px] max-w-[280px] flex-col gap-sm rounded-xl bg-surface/95 p-sm text-on-surface shadow-xl backdrop-blur-xl">
      <div className="flex items-start justify-between gap-sm">
        <div>
          <p className="m-0 text-label-sm font-semibold uppercase tracking-wide text-primary">
            Parking spot
          </p>
          <strong className="mt-1 block text-body-md">{address}</strong>
        </div>
        <StatusBadge status={spot.status} className="shrink-0" />
      </div>

      <div className="flex flex-wrap gap-xs">
        <span className="rounded-full bg-surface-container px-sm py-1 text-label-sm text-on-surface-variant">
          {freshness.label}
        </span>
        <span className="rounded-full bg-surface-container px-sm py-1 text-label-sm text-on-surface-variant">
          {humanizeEnum(spot.parkingContext)}
        </span>
        <span className="rounded-full bg-surface-container px-sm py-1 text-label-sm text-on-surface-variant">
          {humanizeEnum(spot.legalStatus)}
        </span>
      </div>

      <div className="grid gap-1 text-label-sm text-on-surface-variant">
        <span>Expires: {formatInstant(spot.expiresAt)}</span>
        <span>
          Vehicles: {spot.suitableVehicleTypes.map(humanizeEnum).join(', ') || 'Not specified'}
        </span>
      </div>

      <Link
        to={`/spots/${spot.id}`}
        className="mt-xs inline-flex items-center justify-center rounded-full bg-primary px-md py-sm text-label-md font-semibold text-on-primary shadow-sm transition-colors hover:bg-primary-container focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30"
      >
        View spot details
      </Link>
    </div>
  );
}

export function NearbySpotsMap({
  center,
  zoom = DEFAULT_MAP_ZOOM,
  spots,
  onPickCenter,
  height = 320,
  onLocate,
  locating = false,
  showFloatingControls = false,
}: NearbySpotsMapProps) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selectedSpot = spots.find((spot) => spot.id === selectedId) ?? null;

  return (
    <Map
      initialViewState={{ longitude: center.lng, latitude: center.lat, zoom }}
      mapStyle={getMapStyle()}
      dragRotate={false}
      pitchWithRotate={false}
      onClick={(event) => {
        setSelectedId(null);
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

      {spots.map((spot) => (
        <Marker
          key={spot.id}
          longitude={spot.longitude}
          latitude={spot.latitude}
          anchor="center"
        >
          <SpotMarker
            spot={spot}
            selected={selectedId === spot.id}
            onSelect={() => setSelectedId(spot.id)}
          />
        </Marker>
      ))}

      {selectedSpot ? (
        <Popup
          longitude={selectedSpot.longitude}
          latitude={selectedSpot.latitude}
          anchor="bottom"
          offset={12}
          closeOnClick={false}
          onClose={() => setSelectedId(null)}
          maxWidth="none"
          className="parkio-map-popup"
        >
          <SpotPopup spot={selectedSpot} />
        </Popup>
      ) : null}
    </Map>
  );
}
