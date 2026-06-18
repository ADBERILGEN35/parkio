import './maplibreSetup';
import Map, { Marker } from 'react-map-gl/maplibre';
import { DETAIL_ZOOM, getMapStyle } from './mapConfig';

export interface SpotMapProps {
  latitude: number;
  longitude: number;
  height?: number;
}

/** Read-only map centered on a single spot, with one marker. */
export function SpotMap({ latitude, longitude, height = 220 }: SpotMapProps) {
  return (
    <Map
      initialViewState={{ longitude, latitude, zoom: DETAIL_ZOOM }}
      mapStyle={getMapStyle()}
      scrollZoom={false}
      dragRotate={false}
      pitchWithRotate={false}
      style={{ height, width: '100%', borderRadius: '0.5rem' }}
    >
      <Marker longitude={longitude} latitude={latitude} anchor="bottom" />
    </Map>
  );
}
