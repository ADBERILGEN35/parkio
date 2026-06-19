import './maplibreSetup';
import Map, { Marker } from 'react-map-gl/maplibre';
import {
  DEFAULT_MAP_CENTER,
  DEFAULT_PICKER_ZOOM,
  getMapStyle,
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

/** Click-to-set location picker. The chosen point is shown as a marker. */
export function MapPicker({
  latitude,
  longitude,
  onPick,
  height = 280,
  fallbackCenter = DEFAULT_MAP_CENTER,
}: MapPickerProps) {
  const hasMarker = isValidLatLng(latitude, longitude);
  const center = hasMarker
    ? { lat: latitude as number, lng: longitude as number }
    : fallbackCenter;

  return (
    <Map
      initialViewState={{ longitude: center.lng, latitude: center.lat, zoom: DEFAULT_PICKER_ZOOM }}
      mapStyle={getMapStyle()}
      dragRotate={false}
      pitchWithRotate={false}
      onClick={(event) => onPick(event.lngLat.lat, event.lngLat.lng)}
      style={{ height, width: '100%', borderRadius: '0.5rem' }}
    >
      {hasMarker ? (
        <>
          <Marker longitude={center.lng} latitude={center.lat} anchor="bottom" />
          <Recenter lat={center.lat} lng={center.lng} />
        </>
      ) : null}
    </Map>
  );
}
