import { useEffect, useRef } from 'react';
import { useMap } from 'react-leaflet';

/**
 * Imperatively recenters the map when the target coordinates change — a Leaflet
 * `MapContainer` only honors `center` on first render, so external updates
 * (geolocation, manual edits, map clicks) need this.
 *
 * `zoom` is only applied when it actually changes (e.g. snapping closer after a
 * successful locate); recenters driven by map clicks or manual edits keep the
 * user's current zoom level.
 */
export function Recenter({ lat, lng, zoom }: { lat: number; lng: number; zoom?: number }) {
  const map = useMap();
  const prevZoomRef = useRef<number | undefined>(zoom);
  useEffect(() => {
    const zoomChanged = zoom !== undefined && zoom !== prevZoomRef.current;
    prevZoomRef.current = zoom;
    map.setView([lat, lng], zoomChanged ? zoom : map.getZoom());
  }, [lat, lng, zoom, map]);
  return null;
}
