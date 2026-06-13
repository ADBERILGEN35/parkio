import './leafletSetup';
import type { PublicSpot } from '@parkio/types';
import { StatusBadge, cn, getSpotStatusVisual, getTrustFreshnessVisual } from '@parkio/ui';
import L from 'leaflet';
import { CircleMarker, MapContainer, Marker, Popup, TileLayer, useMapEvents } from 'react-leaflet';
import { Link } from 'react-router-dom';
import { MapFloatingControls } from './MapFloatingControls';
import { formatInstant, humanizeEnum } from '@/lib/format';
import { DEFAULT_ZOOM, TILE_ATTRIBUTION, TILE_URL, type LatLng } from './mapConfig';
import { Recenter } from './Recenter';

export interface NearbySpotsMapProps {
  center: LatLng;
  spots: PublicSpot[];
  onPickCenter: (lat: number, lng: number) => void;
  height?: number | string;
  onLocate?: () => void;
  locating?: boolean;
  showFloatingControls?: boolean;
}

function ClickToSetCenter({ onPickCenter }: { onPickCenter: (lat: number, lng: number) => void }) {
  useMapEvents({
    click(event) {
      onPickCenter(event.latlng.lat, event.latlng.lng);
    },
  });
  return null;
}

function spotMarkerIcon(spot: PublicSpot): L.DivIcon {
  const status = getSpotStatusVisual(spot.status);
  const { freshness } = getTrustFreshnessVisual(spot.updatedAt);
  const dimmed = freshness === 'aging' || freshness === 'stale';
  return L.divIcon({
    className: '',
    html: `<span class="${cn(
      'block h-4 w-4 rounded-full border-2 border-white shadow-md',
      status.dotClassName,
      dimmed && 'opacity-60',
    )}"></span>`,
    iconSize: [16, 16],
    iconAnchor: [8, 8],
  });
}

export function NearbySpotsMap({
  center,
  spots,
  onPickCenter,
  height = 320,
  onLocate,
  locating = false,
  showFloatingControls = false,
}: NearbySpotsMapProps) {
  return (
    <MapContainer
      center={[center.lat, center.lng]}
      zoom={DEFAULT_ZOOM}
      zoomControl={false}
      style={{ height, width: '100%' }}
      className="h-full w-full"
    >
      <TileLayer url={TILE_URL} attribution={TILE_ATTRIBUTION} />
      <ClickToSetCenter onPickCenter={onPickCenter} />
      <Recenter lat={center.lat} lng={center.lng} />
      {showFloatingControls && onLocate ? (
        <MapFloatingControls onLocate={onLocate} locating={locating} sidebarOpen />
      ) : null}
      <CircleMarker
        center={[center.lat, center.lng]}
        radius={8}
        pathOptions={{ color: '#0050cb', fillColor: '#0050cb', fillOpacity: 0.5 }}
      />
      {spots.map((spot) => (
        <Marker key={spot.id} position={[spot.latitude, spot.longitude]} icon={spotMarkerIcon(spot)}>
          <Popup>
            <div className="flex min-w-[180px] flex-col gap-xs">
              <strong className="text-body-md text-on-surface">
                {spot.addressText ?? `${spot.latitude}, ${spot.longitude}`}
              </strong>
              <StatusBadge status={spot.status} className="w-fit" />
              <span className="text-label-sm text-on-surface-variant">
                Expires: {formatInstant(spot.expiresAt)}
              </span>
              <span className="text-label-sm text-on-surface-variant">
                Vehicles: {spot.suitableVehicleTypes.map(humanizeEnum).join(', ') || '—'}
              </span>
              <Link to={`/spots/${spot.id}`} className="text-label-sm font-semibold text-primary">
                View spot
              </Link>
            </div>
          </Popup>
        </Marker>
      ))}
    </MapContainer>
  );
}
