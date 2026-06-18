import { useEffect, useRef } from 'react';
import { useMap } from 'react-map-gl/maplibre';

/**
 * Imperatively recenters the map when the target coordinates change — the map is
 * uncontrolled (it owns its view state after first render), so external updates
 * (geolocation, manual edits, map clicks) need this.
 *
 * `zoom` is only applied when it actually changes (e.g. snapping closer after a
 * successful locate); recenters driven by map clicks or manual edits keep the
 * user's current zoom level. The move is eased for a smooth transition.
 */
export function Recenter({ lat, lng, zoom }: { lat: number; lng: number; zoom?: number }) {
  const { current: map } = useMap();
  const prevZoomRef = useRef<number | undefined>(zoom);

  useEffect(() => {
    if (!map) return;
    const zoomChanged = zoom !== undefined && zoom !== prevZoomRef.current;
    prevZoomRef.current = zoom;
    map.easeTo({
      center: [lng, lat],
      zoom: zoomChanged ? zoom : map.getZoom(),
      duration: 600,
    });
  }, [lat, lng, zoom, map]);

  return null;
}
