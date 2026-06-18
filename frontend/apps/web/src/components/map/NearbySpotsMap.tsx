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

/** The colored status dot shown for each spot marker. */
function SpotMarkerDot({ spot }: { spot: PublicSpot }) {
  const status = getSpotStatusVisual(spot.status);
  const { freshness } = getTrustFreshnessVisual(spot.updatedAt);
  const dimmed = freshness === 'aging' || freshness === 'stale';
  return (
    <span
      className={cn(
        'block h-4 w-4 cursor-pointer rounded-full border-2 border-white shadow-md',
        status.dotClassName,
        dimmed && 'opacity-60',
      )}
    />
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
          onClick={(event) => {
            // Keep the map's click-to-set-center from also firing.
            event.originalEvent.stopPropagation();
            setSelectedId(spot.id);
          }}
        >
          <SpotMarkerDot spot={spot} />
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
        >
          <div className="flex min-w-[180px] flex-col gap-xs">
            <strong className="text-body-md text-on-surface">
              {selectedSpot.addressText ?? `${selectedSpot.latitude}, ${selectedSpot.longitude}`}
            </strong>
            <StatusBadge status={selectedSpot.status} className="w-fit" />
            <span className="text-label-sm text-on-surface-variant">
              Expires: {formatInstant(selectedSpot.expiresAt)}
            </span>
            <span className="text-label-sm text-on-surface-variant">
              Vehicles: {selectedSpot.suitableVehicleTypes.map(humanizeEnum).join(', ') || '—'}
            </span>
            <Link
              to={`/spots/${selectedSpot.id}`}
              className="text-label-sm font-semibold text-primary"
            >
              View spot
            </Link>
          </div>
        </Popup>
      ) : null}
    </Map>
  );
}
