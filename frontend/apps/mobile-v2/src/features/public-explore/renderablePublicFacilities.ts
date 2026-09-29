import { isValidLatLng } from '@parkio/geo';
import type { PublicExploreFacility } from '@parkio/types';

/**
 * Canonical renderable public municipal set:
 * - invalid coordinates excluded
 * - first occurrence of a facility id wins
 *
 * DISPLAYED_VISIBLE_COUNT must equal this list length (never municipalTotalInScope).
 */
export function toRenderablePublicFacilities(
  facilities: readonly PublicExploreFacility[],
): PublicExploreFacility[] {
  const seen = new Set<string>();
  const renderable: PublicExploreFacility[] = [];
  for (const facility of facilities) {
    if (seen.has(facility.id)) continue;
    if (!isValidLatLng(facility.latitude, facility.longitude)) continue;
    seen.add(facility.id);
    renderable.push(facility);
  }
  return renderable;
}
