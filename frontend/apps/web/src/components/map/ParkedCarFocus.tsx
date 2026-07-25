import { useEffect, useRef } from 'react';
import { useMap } from 'react-map-gl/maplibre';
import { DETAIL_ZOOM } from './mapConfig';
import { isUsableParkedCoordinate, type ParkedCarFocusRequest } from './parkedCarCoords';

/**
 * Shared map-focus path for the parked car. Must render inside react-map-gl `<Map>`.
 * No-ops until the map instance exists; retries when the map becomes ready.
 */
export function ParkedCarFocus({ request }: { request: ParkedCarFocusRequest | null }) {
  const { current: map } = useMap();
  const appliedTokenRef = useRef<number | null>(null);

  useEffect(() => {
    if (!map || !request) return;
    if (appliedTokenRef.current === request.token) return;
    if (!isUsableParkedCoordinate(request.latitude, request.longitude)) return;
    appliedTokenRef.current = request.token;
    map.flyTo({
      center: [request.longitude, request.latitude],
      zoom: DETAIL_ZOOM,
      duration: 800,
    });
  }, [map, request]);

  return null;
}