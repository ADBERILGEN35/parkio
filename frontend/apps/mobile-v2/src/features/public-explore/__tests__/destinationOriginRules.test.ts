import type { GeocodeResult } from '@parkio/types';
import { DEFAULT_MAP_CENTER } from '@parkio/geo';

/**
 * Pure destination / discovery-origin rules for Wave 2 — keeps PublicExploreScreen
 * behavior testable without mounting MapLibre.
 */

export function nextOriginAfterClearDestination(
  userLocation: { lat: number; lng: number } | null,
): { lat: number; lng: number } {
  return userLocation ?? DEFAULT_MAP_CENTER;
}

export function shouldClearDestinationOnLocateSuccess(): boolean {
  return true;
}

export function shouldClearDestinationOnLocateDenial(hasDestination: boolean): boolean {
  // Denial must not silently wipe a valid destination.
  return !hasDestination;
}

export function discoveryOriginFromDestination(place: GeocodeResult): {
  lat: number;
  lng: number;
} {
  return { lat: place.lat, lng: place.lng };
}

describe('destination / user location separation', () => {
  const place: GeocodeResult = {
    id: 'p1',
    displayName: 'Alsancak, İzmir',
    primary: 'Alsancak',
    secondary: 'İzmir',
    lat: 38.438,
    lng: 27.142,
  };

  it('sets discoveryOrigin from destination coords', () => {
    expect(discoveryOriginFromDestination(place)).toEqual({ lat: 38.438, lng: 27.142 });
  });

  it('clear destination falls back to userLocation then İzmir', () => {
    expect(nextOriginAfterClearDestination({ lat: 38.5, lng: 27.1 })).toEqual({
      lat: 38.5,
      lng: 27.1,
    });
    expect(nextOriginAfterClearDestination(null)).toEqual(DEFAULT_MAP_CENTER);
  });

  it('successful locate clears destination', () => {
    expect(shouldClearDestinationOnLocateSuccess()).toBe(true);
  });

  it('locate denial does not clear destination', () => {
    expect(shouldClearDestinationOnLocateDenial(true)).toBe(false);
    expect(shouldClearDestinationOnLocateDenial(false)).toBe(true);
  });
});
