import { useCallback, useEffect, useRef, useState } from 'react';
import { Linking } from 'react-native';
import * as Location from 'expo-location';
import type { LatLng } from '@parkio/geo';

/**
 * Permission lifecycle, normalized for the UI:
 * - `undetermined`: never asked — show the first-launch prompt.
 * - `prompting`: the OS dialog is open / we're resolving.
 * - `granted`: usable.
 * - `denied`: denied but we may ask again (retry inline).
 * - `blocked`: denied with "don't ask again" — must go to Settings.
 */
export type LocationPermission = 'undetermined' | 'prompting' | 'granted' | 'denied' | 'blocked';

export interface UseLocationResult {
  permission: LocationPermission;
  location: LatLng | null;
  /** True while a position fix is in flight. */
  loading: boolean;
  error: string | null;
  /** Ask for permission (if needed) and fetch the current position. */
  request: () => Promise<LatLng | null>;
  /** Re-fetch the current position (permission already granted). */
  refresh: () => Promise<LatLng | null>;
  /** Open the OS app settings (for the `blocked` state). */
  openSettings: () => void;
}

function toPermission(status: Location.PermissionStatus, canAskAgain: boolean): LocationPermission {
  if (status === 'granted') return 'granted';
  if (status === 'undetermined') return 'undetermined';
  return canAskAgain ? 'denied' : 'blocked';
}

/**
 * Device location with a complete, testable permission flow. Never throws to the
 * caller — failures surface as `error` + a recoverable state. Wraps `expo-location`
 * so the rest of the map feature stays platform-agnostic.
 */
export function useLocation(): UseLocationResult {
  const [permission, setPermission] = useState<LocationPermission>('undetermined');
  const [location, setLocation] = useState<LatLng | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    // Reflect any previously-granted permission without prompting.
    void Location.getForegroundPermissionsAsync().then((res) => {
      if (mounted.current) setPermission(toPermission(res.status, res.canAskAgain));
    });
    return () => {
      mounted.current = false;
    };
  }, []);

  const fetchPosition = useCallback(async (): Promise<LatLng | null> => {
    setLoading(true);
    setError(null);
    try {
      const pos = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.Balanced,
      });
      const coords: LatLng = { lat: pos.coords.latitude, lng: pos.coords.longitude };
      if (mounted.current) setLocation(coords);
      return coords;
    } catch {
      // Fall back to the last known fix before giving up.
      try {
        const last = await Location.getLastKnownPositionAsync();
        if (last) {
          const coords: LatLng = { lat: last.coords.latitude, lng: last.coords.longitude };
          if (mounted.current) setLocation(coords);
          return coords;
        }
      } catch {
        /* ignore */
      }
      if (mounted.current) setError('We couldn’t get your location. Please try again.');
      return null;
    } finally {
      if (mounted.current) setLoading(false);
    }
  }, []);

  const request = useCallback(async (): Promise<LatLng | null> => {
    setPermission('prompting');
    setError(null);
    let res: Location.LocationPermissionResponse;
    try {
      res = await Location.requestForegroundPermissionsAsync();
    } catch {
      if (mounted.current) {
        setPermission('denied');
        setError('Couldn’t request location permission.');
      }
      return null;
    }
    const next = toPermission(res.status, res.canAskAgain);
    if (mounted.current) setPermission(next);
    if (next !== 'granted') return null;
    return fetchPosition();
  }, [fetchPosition]);

  const refresh = useCallback(async (): Promise<LatLng | null> => {
    if (permission !== 'granted') return request();
    return fetchPosition();
  }, [permission, request, fetchPosition]);

  const openSettings = useCallback(() => {
    void Linking.openSettings();
  }, []);

  return { permission, location, loading, error, request, refresh, openSettings };
}
