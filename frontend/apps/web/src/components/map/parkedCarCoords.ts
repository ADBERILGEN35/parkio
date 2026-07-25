import { isValidLatLng } from './mapConfig';

/** Imperative flyTo request keyed by a monotonic token (bump to re-focus). */
export type ParkedCarFocusRequest = {
  latitude: number;
  longitude: number;
  token: number;
};

/** Geographic bounds for parked-car focus/marker (stricter than map center finiteness). */
export function isUsableParkedCoordinate(latitude: number, longitude: number): boolean {
  return (
    isValidLatLng(latitude, longitude) &&
    latitude >= -90 &&
    latitude <= 90 &&
    longitude >= -180 &&
    longitude <= 180
  );
}