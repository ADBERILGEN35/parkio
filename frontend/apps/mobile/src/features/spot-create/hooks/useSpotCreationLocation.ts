import { useCallback, useEffect, useRef, useState } from 'react';
import { Linking } from 'react-native';
import * as Location from 'expo-location';
import type { LatLng } from '@parkio/geo';
import { isGpsAccuracyAcceptable } from '../lib/locationAccuracy';

export type SpotLocationStatus =
  | 'idle'
  | 'prompting'
  | 'locating'
  | 'ready'
  | 'low-accuracy'
  | 'denied'
  | 'blocked'
  | 'unavailable';

export interface SpotLocationFix {
  center: LatLng;
  accuracyMeters: number | null;
}

export function useSpotCreationLocation() {
  const [status, setStatus] = useState<SpotLocationStatus>('idle');
  const [fix, setFix] = useState<SpotLocationFix | null>(null);
  const [error, setError] = useState<string | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const acquire = useCallback(async (): Promise<SpotLocationFix | null> => {
    setError(null);
    setStatus('prompting');
    let permission: Location.LocationPermissionResponse;
    try {
      permission = await Location.requestForegroundPermissionsAsync();
    } catch {
      if (mounted.current) {
        setStatus('unavailable');
        setError('Could not request location access.');
      }
      return null;
    }

    if (permission.status !== 'granted') {
      if (mounted.current) {
        setStatus(permission.canAskAgain ? 'denied' : 'blocked');
        setError('Location access is required to create a spot.');
      }
      return null;
    }

    if (mounted.current) setStatus('locating');
    try {
      const position = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.Highest,
      });
      const nextFix: SpotLocationFix = {
        center: { lat: position.coords.latitude, lng: position.coords.longitude },
        accuracyMeters: position.coords.accuracy,
      };
      if (mounted.current) {
        setFix(nextFix);
        setStatus(isGpsAccuracyAcceptable(nextFix.accuracyMeters) ? 'ready' : 'low-accuracy');
      }
      return nextFix;
    } catch {
      if (mounted.current) {
        setStatus('unavailable');
        setError('GPS is unavailable. Move outside or try again.');
      }
      return null;
    }
  }, []);

  const openSettings = useCallback(() => {
    void Linking.openSettings();
  }, []);

  return { status, fix, error, acquire, openSettings };
}
