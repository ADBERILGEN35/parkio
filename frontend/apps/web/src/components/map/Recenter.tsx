import { useEffect } from 'react';
import { useMap } from 'react-leaflet';

/**
 * Imperatively recenters the map when the target coordinates change — a Leaflet
 * `MapContainer` only honors `center` on first render, so external updates
 * (geolocation, manual edits, map clicks) need this.
 */
export function Recenter({ lat, lng, zoom }: { lat: number; lng: number; zoom?: number }) {
  const map = useMap();
  useEffect(() => {
    map.setView([lat, lng], zoom ?? map.getZoom());
  }, [lat, lng, zoom, map]);
  return null;
}
