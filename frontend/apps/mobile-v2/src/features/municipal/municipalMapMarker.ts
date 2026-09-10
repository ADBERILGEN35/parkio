import { isValidLatLng, type MunicipalOccupancyPresentationKind } from '@parkio/geo';
import type { MunicipalFacility } from '@parkio/types';
import {
  presentMunicipalFacility,
  type MunicipalFacilityPresentation,
} from '@/features/municipal/presentMunicipalFacility';

/**
 * Compact WebView / map marker model for municipal facilities.
 * Separate from {@link PublicSpot} / {@link MapSpotMarker} — never fused.
 * Coordinates are bridge-only; never rendered as text in RN UI.
 */
export interface MapMunicipalMarker {
  id: string;
  lat: number;
  lng: number;
  occupancyKind: MunicipalOccupancyPresentationKind;
  /** Screen-reader / a11y intent string (localized by caller). */
  accessibilityLabel: string;
}

export interface MunicipalMarkerBuildOptions {
  /** Localized name used in accessibilityLabel when displayName is null. */
  unnamedLabel: string;
  /** Localized occupancy state words keyed by presentation kind. */
  occupancyLabels: Record<MunicipalOccupancyPresentationKind, string>;
}

function buildAccessibilityLabel(
  presented: MunicipalFacilityPresentation,
  options: MunicipalMarkerBuildOptions,
): string {
  const name = presented.displayName?.trim() || options.unnamedLabel;
  const occupancy = options.occupancyLabels[presented.occupancyKind];
  const source = presented.sourceLine ?? '';
  return [name, occupancy, source].filter((part) => part.length > 0).join(', ');
}

/**
 * Map a municipal facility DTO to a bridge marker, or null when coordinates are invalid.
 * Presentation values come from {@link presentMunicipalFacility} (shared geo semantics).
 */
export function toMapMunicipalMarker(
  facility: MunicipalFacility,
  options: MunicipalMarkerBuildOptions,
): MapMunicipalMarker | null {
  if (!isValidLatLng(facility.latitude, facility.longitude)) {
    return null;
  }
  const presented = presentMunicipalFacility(facility);
  return {
    id: facility.id,
    lat: facility.latitude,
    lng: facility.longitude,
    occupancyKind: presented.occupancyKind,
    accessibilityLabel: buildAccessibilityLabel(presented, options),
  };
}

/**
 * Deterministic marker list: invalid coords dropped; first occurrence of a facility id wins.
 */
export function toMapMunicipalMarkers(
  facilities: readonly MunicipalFacility[],
  options: MunicipalMarkerBuildOptions,
): MapMunicipalMarker[] {
  const seen = new Set<string>();
  const markers: MapMunicipalMarker[] = [];
  for (const facility of facilities) {
    if (seen.has(facility.id)) {
      continue;
    }
    const marker = toMapMunicipalMarker(facility, options);
    if (!marker) {
      continue;
    }
    seen.add(facility.id);
    markers.push(marker);
  }
  return markers;
}
