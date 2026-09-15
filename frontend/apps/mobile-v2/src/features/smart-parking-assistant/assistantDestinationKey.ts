import type { Destination } from '@parkio/types';

/** Stable identity for destination confirmation dedupe + recommendation cache keys. */
export function assistantDestinationIdentityKey(destination: Destination): string {
  if (destination.placeIdentity?.provider && destination.placeIdentity.providerPlaceId) {
    return `identity:${destination.placeIdentity.provider}:${destination.placeIdentity.providerPlaceId}`;
  }
  const factor = 1e5;
  const lat = (Math.round(destination.latitude * factor) / factor).toFixed(5);
  const lng = (Math.round(destination.longitude * factor) / factor).toFixed(5);
  return `coord:${lat}:${lng}`;
}
