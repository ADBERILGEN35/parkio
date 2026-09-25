import { isValidLatLng } from '@parkio/geo';
import type { MunicipalFacility } from '@parkio/types';
import {
  presentMunicipalFacility,
  type MunicipalFacilityPresentation,
} from '@/features/municipal/presentMunicipalFacility';

/** Optional discovery-context distance passed via route params (meters). */
export function parseOptionalDistanceMeters(raw: unknown): number | null {
  if (raw == null) return null;
  const value = typeof raw === 'string' ? Number(raw) : typeof raw === 'number' ? raw : NaN;
  if (!Number.isFinite(value) || value < 0) return null;
  return value;
}

/** Safe facility id from Expo Router params — blank/invalid → empty (no fetch). */
export function parseFacilityRouteId(raw: unknown): string {
  if (typeof raw !== 'string') return '';
  return raw.trim();
}

function meaningfulText(value: string | null | undefined): string | null {
  if (value == null) return null;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function operatorIsUseful(
  operatorName: string | null,
  sourceLine: string | null,
): string | null {
  const operator = meaningfulText(operatorName);
  if (!operator) return null;
  if (!sourceLine) return operator;
  const normalizedOperator = operator.toLocaleLowerCase('tr-TR');
  const normalizedSource = sourceLine.toLocaleLowerCase('tr-TR');
  if (normalizedOperator === normalizedSource) return null;
  if (normalizedSource.includes(normalizedOperator)) return null;
  return operator;
}

export type MunicipalDetailFacilityTypeKey = 'onStreet' | 'offStreet';

/**
 * Testable display-field policy for municipal facility detail.
 * Never invents hours/prices/contact; never exposes raw keys or coordinates.
 */
export interface MunicipalFacilityDetailFields {
  title: string;
  sourceLine: string | null;
  facilityTypeKey: MunicipalDetailFacilityTypeKey | null;
  distanceMeters: number | null;
  occupancyKind: MunicipalFacilityPresentation['occupancyKind'];
  /** True when live/aging metrics may be shown (availableSpaces is a real number, including 0). */
  showLiveMetrics: boolean;
  availableSpaces: number | null;
  occupiedSpaces: number | null;
  capacityTotal: number | null;
  addressText: string | null;
  operatorName: string | null;
  lastUpdatedAt: string | null;
  canOpenInMaps: boolean;
  latitude: number;
  longitude: number;
  showFacilityInfoSection: boolean;
  showLocationSection: boolean;
}

export interface BuildMunicipalDetailFieldsOptions {
  unnamedLabel: string;
  distanceMeters?: number | null;
}

export function buildMunicipalFacilityDetailFields(
  facility: MunicipalFacility,
  options: BuildMunicipalDetailFieldsOptions,
): MunicipalFacilityDetailFields {
  const presented = presentMunicipalFacility(facility);
  const title = meaningfulText(presented.displayName) ?? options.unnamedLabel;
  const sourceLine = meaningfulText(presented.sourceLine);
  const addressText = meaningfulText(presented.addressText);
  const operatorName = operatorIsUseful(presented.operatorName, sourceLine);

  const facilityTypeKey: MunicipalDetailFacilityTypeKey | null =
    presented.facilityType === 'ON_STREET'
      ? 'onStreet'
      : presented.facilityType === 'OFF_STREET'
        ? 'offStreet'
        : null;

  const showLiveMetrics =
    (presented.occupancyKind === 'live' || presented.occupancyKind === 'aging') &&
    presented.availableSpaces != null;

  const canOpenInMaps = isValidLatLng(facility.latitude, facility.longitude);

  const showFacilityInfoSection = facilityTypeKey != null || operatorName != null;
  const showLocationSection =
    addressText != null ||
    (typeof options.distanceMeters === 'number' && Number.isFinite(options.distanceMeters)) ||
    canOpenInMaps;

  return {
    title,
    sourceLine,
    facilityTypeKey,
    distanceMeters:
      typeof options.distanceMeters === 'number' && Number.isFinite(options.distanceMeters)
        ? options.distanceMeters
        : null,
    occupancyKind: presented.occupancyKind,
    showLiveMetrics,
    availableSpaces: showLiveMetrics ? presented.availableSpaces : null,
    occupiedSpaces: showLiveMetrics ? presented.occupiedSpaces : null,
    capacityTotal: showLiveMetrics ? presented.capacityTotal : null,
    addressText,
    operatorName,
    lastUpdatedAt: meaningfulText(presented.lastUpdatedAt),
    canOpenInMaps,
    latitude: facility.latitude,
    longitude: facility.longitude,
    showFacilityInfoSection,
    showLocationSection,
  };
}
