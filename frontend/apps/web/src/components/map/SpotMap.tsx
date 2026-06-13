import './leafletSetup';
import { MapContainer, Marker, TileLayer } from 'react-leaflet';
import { DETAIL_ZOOM, TILE_ATTRIBUTION, TILE_URL } from './mapConfig';

export interface SpotMapProps {
  latitude: number;
  longitude: number;
  height?: number;
}

/** Read-only map centered on a single spot, with one marker. */
export function SpotMap({ latitude, longitude, height = 220 }: SpotMapProps) {
  const position: [number, number] = [latitude, longitude];

  return (
    <MapContainer
      center={position}
      zoom={DETAIL_ZOOM}
      scrollWheelZoom={false}
      style={{ height, width: '100%', borderRadius: '0.5rem' }}
    >
      <TileLayer url={TILE_URL} attribution={TILE_ATTRIBUTION} />
      <Marker position={position} />
    </MapContainer>
  );
}
