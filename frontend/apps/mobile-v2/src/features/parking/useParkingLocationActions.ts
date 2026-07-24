import { useCallback, useEffect, useRef, useState } from 'react';
import { Platform, Share } from 'react-native';
import * as Linking from 'expo-linking';
import { useAuthStore } from '@/state/authStore';
import { useT } from '@/i18n/LocaleProvider';
import { useToast } from '@/providers/ToastProvider';
import {
  trackProductEvent,
  type ParkingActionFailureReason,
} from '@/services/productAnalytics';
import {
  buildParkingMapsHttpsUrl,
  buildParkingNavigationUrl,
  buildParkingShareContent,
  isValidParkingDestination,
  type ParkingNavPlatform,
} from './parkingLocationLinks';

export type ParkingLocationActionPhase = 'idle' | 'navigating' | 'sharing';

export interface ParkingLocationActions {
  phase: ParkingLocationActionPhase;
  busy: boolean;
  destinationValid: boolean;
  navigateDisabled: boolean;
  shareDisabled: boolean;
  navigate: () => Promise<void>;
  share: () => Promise<void>;
}

function mapPlatform(): ParkingNavPlatform {
  if (Platform.OS === 'ios') return 'ios';
  if (Platform.OS === 'android') return 'android';
  return 'default';
}

function failureReason(error: unknown, fallback: ParkingActionFailureReason): ParkingActionFailureReason {
  if (error instanceof Error) {
    if (error.message === 'invalid_destination') return 'invalid_destination';
    if (error.message === 'unsupported_url') return 'unsupported_url';
    if (error.message === 'share_unavailable') return 'share_unavailable';
  }
  return fallback;
}

/**
 * Return-to-car navigation + native location share for an ACTIVE ParkingSession
 * (S1-P0-10). Coordinates remain in-memory; no drafts / retries / permission asks.
 */
export function useParkingLocationActions(options: {
  sessionId: string | null;
  latitude: number | null | undefined;
  longitude: number | null | undefined;
  /** When terminal complete/cancel is in flight, disable local platform actions. */
  terminalBusy: boolean;
}): ParkingLocationActions {
  const t = useT();
  const toast = useToast();
  const userId = useAuthStore((s) => s.user?.id ?? null);
  const sessionEpoch = useAuthStore((s) => s.sessionEpoch);

  const [phase, setPhase] = useState<ParkingLocationActionPhase>('idle');
  const [boundIdentity, setBoundIdentity] = useState(
    `${userId ?? 'anon'}:${sessionEpoch}:${options.sessionId ?? 'none'}`,
  );

  const identityRef = useRef(boundIdentity);
  const phaseRef = useRef(phase);
  const inFlightRef = useRef(false);

  const identityKey = `${userId ?? 'anon'}:${sessionEpoch}:${options.sessionId ?? 'none'}`;
  if (boundIdentity !== identityKey) {
    setBoundIdentity(identityKey);
    setPhase('idle');
  }

  useEffect(() => {
    identityRef.current = identityKey;
    phaseRef.current = 'idle';
    inFlightRef.current = false;
  }, [identityKey]);

  useEffect(() => {
    phaseRef.current = phase;
  }, [phase]);

  const destinationValid = isValidParkingDestination(options.latitude, options.longitude);
  const busy = phase !== 'idle';
  const blocked = busy || options.terminalBusy || !options.sessionId || !destinationValid;

  const reportFailure = useCallback(
    (action: 'navigation' | 'share', reason: ParkingActionFailureReason, toastKey: 'nav' | 'share') => {
      trackProductEvent('parking_action_failed', {
        platform: Platform.OS,
        action,
        reason,
      });
      toast.show(
        toastKey === 'nav'
          ? t('parkingSession.navigate.failed')
          : t('parkingSession.share.failed'),
        'error',
      );
    },
    [t, toast],
  );

  const navigate = useCallback(async () => {
    if (blocked || phaseRef.current !== 'idle' || inFlightRef.current) {
      return;
    }
    if (!destinationValid || options.latitude == null || options.longitude == null) {
      reportFailure('navigation', 'invalid_destination', 'nav');
      return;
    }

    const token = `${userId ?? 'anon'}:${sessionEpoch}:${options.sessionId ?? 'none'}`;
    inFlightRef.current = true;
    setPhase('navigating');

    const lat = options.latitude;
    const lng = options.longitude;
    const label = t('parkingSession.navigate.mapLabel');

    try {
      const primary = buildParkingNavigationUrl(lat, lng, mapPlatform(), label);
      const fallback = buildParkingMapsHttpsUrl(lat, lng);

      try {
        await Linking.openURL(primary);
      } catch {
        try {
          await Linking.openURL(fallback);
        } catch {
          throw new Error('platform_open_failed');
        }
      }

      if (identityRef.current !== token) {
        return;
      }
      trackProductEvent('return_to_car_clicked', { platform: Platform.OS });
    } catch (error) {
      if (identityRef.current !== token) {
        return;
      }
      reportFailure(
        'navigation',
        failureReason(error, 'platform_open_failed'),
        'nav',
      );
    } finally {
      if (identityRef.current === token) {
        inFlightRef.current = false;
        setPhase('idle');
      }
    }
  }, [
    blocked,
    destinationValid,
    options.latitude,
    options.longitude,
    options.sessionId,
    reportFailure,
    sessionEpoch,
    t,
    userId,
  ]);

  const share = useCallback(async () => {
    if (blocked || phaseRef.current !== 'idle' || inFlightRef.current) {
      return;
    }
    if (!destinationValid || options.latitude == null || options.longitude == null) {
      reportFailure('share', 'invalid_destination', 'share');
      return;
    }

    const token = `${userId ?? 'anon'}:${sessionEpoch}:${options.sessionId ?? 'none'}`;
    inFlightRef.current = true;
    setPhase('sharing');

    try {
      if (typeof Share.share !== 'function') {
        throw new Error('share_unavailable');
      }

      const content = buildParkingShareContent(
        options.latitude,
        options.longitude,
        t('parkingSession.share.messageLead'),
      );

      const result = await Share.share(
        Platform.OS === 'ios'
          ? { message: content.message, url: content.url }
          : { message: content.message },
      );

      if (identityRef.current !== token) {
        return;
      }

      if (result.action === Share.dismissedAction) {
        // User dismissed the sheet — not a failure, not a share success.
        return;
      }

      if (result.action === Share.sharedAction) {
        trackProductEvent('parking_location_shared', { platform: Platform.OS });
      }
    } catch (error) {
      if (identityRef.current !== token) {
        return;
      }
      // Some platforms reject on dismiss; treat only real errors as failures.
      const message = error instanceof Error ? error.message : '';
      if (/cancel|dismiss/i.test(message)) {
        return;
      }
      reportFailure('share', failureReason(error, 'platform_share_failed'), 'share');
    } finally {
      if (identityRef.current === token) {
        inFlightRef.current = false;
        setPhase('idle');
      }
    }
  }, [
    blocked,
    destinationValid,
    options.latitude,
    options.longitude,
    options.sessionId,
    reportFailure,
    sessionEpoch,
    t,
    userId,
  ]);

  return {
    phase,
    busy,
    destinationValid,
    navigateDisabled: blocked,
    shareDisabled: blocked,
    navigate,
    share,
  };
}
