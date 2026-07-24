import { useCallback, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  CancellationError,
  NetworkError,
  TimeoutError,
  UnauthorizedError,
  ValidationError,
  createIdempotencyKey,
  isParkioApiError,
} from '@parkio/api-client';
import type { ParkingSessionResponse, StartParkingSessionRequest } from '@parkio/types';
import { parkingKeys } from '@/data/keys';
import { activeParkingSessionQueryOptions } from '@/data/query-options/parking';
import type { LocationState } from '@/features/map/hooks';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { parkingApi } from '@/services/api';
import { useAuthStore } from '@/state/authStore';

/** Stable domain conflict when the user already has an ACTIVE session. */
export const ACTIVE_PARKING_SESSION_EXISTS = 'ACTIVE_PARKING_SESSION_EXISTS';

export type StartParkingPhase =
  | 'idle'
  | 'requestingPermission'
  | 'acquiringLocation'
  | 'submitting'
  | 'reconciling'
  | 'permissionDeniedAsk'
  | 'permissionDeniedSettings'
  | 'locationFailed'
  | 'offline'
  | 'ambiguous'
  | 'reconcileFailed'
  | 'rejected';

export interface StartParkingSessionControls {
  phase: StartParkingPhase;
  /** True while a start or reconcile is in flight (blocks double-submit). */
  busy: boolean;
  /** Bounded in-memory key for the current submitted attempt (null until submit). */
  attemptKey: string | null;
  start: () => Promise<void>;
  retry: () => Promise<void>;
  reset: () => void;
}

function isValidCoordinate(latitude: number, longitude: number): boolean {
  return (
    Number.isFinite(latitude) &&
    Number.isFinite(longitude) &&
    latitude >= -90 &&
    latitude <= 90 &&
    longitude >= -180 &&
    longitude <= 180
  );
}

function isAmbiguousTransport(error: unknown): boolean {
  return error instanceof NetworkError || error instanceof TimeoutError;
}

function isActiveConflict(error: unknown): boolean {
  return isParkioApiError(error) && error.code === ACTIVE_PARKING_SESSION_EXISTS;
}

function isConclusivePreCommitRejection(error: unknown): boolean {
  return (
    error instanceof ValidationError ||
    error instanceof UnauthorizedError ||
    (isParkioApiError(error) && error.status >= 400 && error.status < 500 && !isActiveConflict(error))
  );
}

/**
 * Manual ParkingSession start (S1-P0-03).
 * One caller-owned idempotency key per submitted attempt; reused on ambiguous transport.
 * ACTIVE_PARKING_SESSION_EXISTS reconciles via active-session refetch.
 */
export function useStartParkingSession(location: LocationState): StartParkingSessionControls {
  const queryClient = useQueryClient();
  const online = useOnlineStatus();
  const userId = useAuthStore((s) => s.user?.id ?? null);
  const sessionEpoch = useAuthStore((s) => s.sessionEpoch);

  const [phase, setPhase] = useState<StartParkingPhase>('idle');
  const [attemptKey, setAttemptKey] = useState<string | null>(null);
  const [boundIdentity, setBoundIdentity] = useState(`${userId ?? 'anon'}:${sessionEpoch}`);

  const keyRef = useRef<string | null>(null);
  const inFlightRef = useRef(false);

  const identityKey = `${userId ?? 'anon'}:${sessionEpoch}`;
  if (boundIdentity !== identityKey) {
    // Auth identity changed — drop visible attempt state for the next user.
    setBoundIdentity(identityKey);
    setAttemptKey(null);
    setPhase('idle');
  }

  const clearAttempt = useCallback(() => {
    keyRef.current = null;
    setAttemptKey(null);
    setPhase('idle');
    inFlightRef.current = false;
  }, []);

  const stillSameUser = useCallback((startedUserId: string | null, startedEpoch: number) => {
    const state = useAuthStore.getState();
    return (state.user?.id ?? null) === startedUserId && state.sessionEpoch === startedEpoch;
  }, []);

  const applyActiveSession = useCallback(
    (session: ParkingSessionResponse) => {
      queryClient.setQueryData(parkingKeys.activeSession(), session);
    },
    [queryClient],
  );

  const reconcileActive = useCallback(async (): Promise<ParkingSessionResponse | null> => {
    const session = await queryClient.fetchQuery(activeParkingSessionQueryOptions());
    if (session) {
      applyActiveSession(session);
    } else {
      queryClient.setQueryData(parkingKeys.activeSession(), null);
    }
    return session;
  }, [applyActiveSession, queryClient]);

  const syncAttemptBoundary = useCallback(() => {
    const state = useAuthStore.getState();
    const nextIdentity = `${state.user?.id ?? 'anon'}:${state.sessionEpoch}`;
    if (nextIdentity !== boundIdentity) {
      keyRef.current = null;
      inFlightRef.current = false;
    }
  }, [boundIdentity]);

  const runStart = useCallback(async () => {
    syncAttemptBoundary();
    if (inFlightRef.current) {
      return;
    }

    const startedUserId = userId;
    const startedEpoch = sessionEpoch;
    inFlightRef.current = true;

    try {
      if (!online) {
        setPhase('offline');
        return;
      }

      if (location.status !== 'granted') {
        if (location.status === 'denied' && !location.canAskAgain) {
          setPhase('permissionDeniedSettings');
          return;
        }
        setPhase('requestingPermission');
        const grantedPosition = await location.request();
        if (!stillSameUser(startedUserId, startedEpoch)) {
          return;
        }
        if (!grantedPosition) {
          setPhase(location.getCanAskAgain() ? 'permissionDeniedAsk' : 'permissionDeniedSettings');
          return;
        }
      }

      setPhase('acquiringLocation');
      const position = location.position ?? (await location.refresh());
      if (!stillSameUser(startedUserId, startedEpoch)) {
        return;
      }
      if (!position || !isValidCoordinate(position.lat, position.lng)) {
        setPhase('locationFailed');
        return;
      }

      const body: StartParkingSessionRequest = {
        latitude: position.lat,
        longitude: position.lng,
      };

      const idempotencyKey = (keyRef.current ??= createIdempotencyKey());
      setAttemptKey(idempotencyKey);

      setPhase('submitting');
      try {
        const session = await parkingApi.startParkingSession(body, idempotencyKey);
        if (!stillSameUser(startedUserId, startedEpoch)) {
          return;
        }
        applyActiveSession(session);
        keyRef.current = null;
        setAttemptKey(null);
        setPhase('idle');
      } catch (error) {
        if (!stillSameUser(startedUserId, startedEpoch)) {
          return;
        }

        if (error instanceof CancellationError) {
          setPhase('ambiguous');
          return;
        }

        if (isActiveConflict(error)) {
          setPhase('reconciling');
          try {
            const existing = await reconcileActive();
            if (!stillSameUser(startedUserId, startedEpoch)) {
              return;
            }
            if (existing) {
              keyRef.current = null;
              setAttemptKey(null);
              setPhase('idle');
              return;
            }
            setPhase('reconcileFailed');
          } catch {
            if (!stillSameUser(startedUserId, startedEpoch)) {
              return;
            }
            setPhase('reconcileFailed');
          }
          return;
        }

        if (isAmbiguousTransport(error)) {
          setPhase('reconciling');
          try {
            const existing = await reconcileActive();
            if (!stillSameUser(startedUserId, startedEpoch)) {
              return;
            }
            if (existing) {
              keyRef.current = null;
              setAttemptKey(null);
              setPhase('idle');
              return;
            }
          } catch {
            // Fall through to ambiguous retry.
          }
          if (!stillSameUser(startedUserId, startedEpoch)) {
            return;
          }
          setPhase('ambiguous');
          return;
        }

        if (isConclusivePreCommitRejection(error)) {
          keyRef.current = null;
          setAttemptKey(null);
          setPhase('rejected');
          return;
        }

        setPhase('ambiguous');
      }
    } finally {
      if (stillSameUser(startedUserId, startedEpoch)) {
        inFlightRef.current = false;
      }
    }
  }, [
    applyActiveSession,
    location,
    online,
    reconcileActive,
    sessionEpoch,
    stillSameUser,
    syncAttemptBoundary,
    userId,
  ]);

  const clearKeyIfNotSubmitted = useCallback(() => {
    keyRef.current = null;
    setAttemptKey(null);
  }, []);

  const start = useCallback(async () => {
    syncAttemptBoundary();
    if (
      phase === 'permissionDeniedAsk' ||
      phase === 'permissionDeniedSettings' ||
      phase === 'locationFailed' ||
      phase === 'offline' ||
      phase === 'rejected'
    ) {
      clearKeyIfNotSubmitted();
    }
    await runStart();
  }, [clearKeyIfNotSubmitted, phase, runStart, syncAttemptBoundary]);

  const retry = useCallback(async () => {
    syncAttemptBoundary();
    if (phase === 'ambiguous' || phase === 'reconcileFailed') {
      await runStart();
      return;
    }
    clearKeyIfNotSubmitted();
    await runStart();
  }, [clearKeyIfNotSubmitted, phase, runStart, syncAttemptBoundary]);

  const reset = useCallback(() => {
    syncAttemptBoundary();
    clearAttempt();
  }, [clearAttempt, syncAttemptBoundary]);

  const busy =
    phase === 'requestingPermission' ||
    phase === 'acquiringLocation' ||
    phase === 'submitting' ||
    phase === 'reconciling';

  return { phase, busy, attemptKey, start, retry, reset };
}