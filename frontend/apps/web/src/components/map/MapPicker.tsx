import './leafletSetup';
import { MapContainer, Marker, TileLayer, useMapEvents } from 'react-leaflet';
import {
  DEFAULT_MAP_CENTER,
  DEFAULT_ZOOM,
  TILE_ATTRIBUTION,
  TILE_URL,
  isValidLatLng,
} from './mapConfig';
import { Recenter } from './Recenter';

export interface MapPickerProps {
  latitude: number | null;
  longitude: number | null;
  onPick: (lat: number, lng: number) => void;
  height?: number;
  /** Center shown before a point is picked. Defaults to the İzmir beta center. */
  fallbackCenter?: { lat: number; lng: number };
}

function ClickToPick({ onPick }: { onPick: (lat: number, lng: number) => void }) {
  useMapEvents({
    click(event) {
      onPick(event.latlng.lat, event.latlng.lng);
    },
  });
  return null;
}

/** Click-to-set location picker. The chosen point is shown as a draggable-free marker. */
export function MapPicker({
  latitude,
  longitude,
  onPick,
  height = 280,
  fallbackCenter = DEFAULT_MAP_CENTER,
}: MapPickerProps) {
  const hasMarker = isValidLatLng(latitude, longitude);
  const center: [number, number] = hasMarker
    ? [latitude as number, longitude as number]
    : [fallbackCenter.lat, fallbackCenter.lng];

  return (
    <MapContainer
      center={center}
      zoom={DEFAULT_ZOOM}
      style={{ height, width: '100%', borderRadius: '0.5rem' }}
    >
      <TileLayer url={TILE_URL} attribution={TILE_ATTRIBUTION} />
      <ClickToPick onPick={onPick} />
      {hasMarker ? (
        <>
          <Marker position={center} />
          <Recenter lat={center[0]} lng={center[1]} />
        </>
      ) : null}
    </MapContainer>
  );
}
