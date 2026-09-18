import { LngLatBounds } from 'maplibre-gl';
import { useEffect, useRef } from 'react';
import { useMap } from 'react-map-gl/maplibre';
import type { LatLng } from './mapConfig';

/** Keep local discovery context readable after locate / destination framing. */
const MAX_FIT_ZOOM = 15;

const FIT_PADDING = {
  top: 140,
  bottom: 96,
  left: 48,
  right: 72,
} as const;

export interface FitDiscoveryFrameProps {
  /** Discovery anchor (user location or destination) — always included in the frame. */
  anchor: LatLng;
  /** Renderable municipal parking coordinates currently shown as green pins. */
  points: readonly LatLng[];
  /** Stable key for the current discovery response (origin + facility ids). */
  revision: string;
  /** When false, skip framing (e.g. while a fetch is in flight). */
  enabled: boolean;
}

/**
 * Fits the map to the discovery anchor plus visible municipal markers so the
 * summary count and on-screen green pins stay coherent after locate/destination.
 * No-ops when there are no renderable parking points (Recenter owns empty locate).
 */
export function FitDiscoveryFrame({
  anchor,
  points,
  revision,
  enabled,
}: FitDiscoveryFrameProps) {
  const { current: map } = useMap();
  const appliedRevisionRef = useRef<string | null>(null);

  useEffect(() => {
    if (!map || !enabled || points.length === 0) return;
    if (appliedRevisionRef.current === revision) return;

    const apply = () => {
      if (appliedRevisionRef.current === revision) return;
      appliedRevisionRef.current = revision;

      const bounds = new LngLatBounds(
        [anchor.lng, anchor.lat],
        [anchor.lng, anchor.lat],
      );
      for (const point of points) {
        bounds.extend([point.lng, point.lat]);
      }

      map.fitBounds(bounds, {
        padding: FIT_PADDING,
        maxZoom: MAX_FIT_ZOOM,
        duration: 700,
      });
    };

    // Let an in-flight Recenter easeTo settle briefly, then frame facilities.
    const timer = window.setTimeout(() => {
      if (!map.isStyleLoaded()) {
        map.once('load', apply);
        return;
      }
      apply();
    }, 80);

    return () => {
      window.clearTimeout(timer);
      map.off('load', apply);
    };
  }, [map, enabled, revision, anchor.lat, anchor.lng, points]);

  return null;
}
