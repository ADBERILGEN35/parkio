import type { MunicipalFacility, PublicExploreFacility } from '@parkio/types';
import { toMunicipalFacilityFromPublicExplore } from './toMunicipalFacilityFromPublicExplore';

/** In-memory public preview selection — never fetches private facility detail. */
export function selectPublicFacilityForPreview(
  facilities: readonly PublicExploreFacility[],
  id: string | null,
): MunicipalFacility | null {
  if (!id) return null;
  const row = facilities.find((f) => f.id === id);
  return row ? toMunicipalFacilityFromPublicExplore(row) : null;
}
