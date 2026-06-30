import { presentSpot } from '@parkio/geo';
import type { PublicSpot } from '@parkio/types';

/** Minimal GeoJSON FeatureCollection shape the MapLibre `spots` source consumes. */
export interface SpotFeatureCollection {
  type: 'FeatureCollection';
  features: {
    type: 'Feature';
    geometry: { type: 'Point'; coordinates: [number, number] };
    properties: { id: string; tone: string; selected: boolean };
  }[];
}

/**
 * Convert public spots into a GeoJSON FeatureCollection for the map source.
 * `tone` is derived from the spot's real status via the shared {@link presentSpot}
 * (no fabricated data); `selected` drives the highlight ring layer.
 */
export function spotsToGeoJson(
  spots: PublicSpot[],
  selectedId: string | null,
): SpotFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: spots.map((spot) => ({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [spot.longitude, spot.latitude] },
      properties: {
        id: spot.id,
        tone: presentSpot(spot).tone,
        selected: spot.id === selectedId,
      },
    })),
  };
}
