import { useCallback, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  NetworkError,
  TimeoutError,
  UnauthorizedError,
  ValidationError,
  createIdempotencyKey,
  isParkioApiError,
} from '@parkio/api-client';
import type { ParkedCarTargetRef, ParkingSessionResponse, SpaParkHereOriginSurface } from '@parkio/types';
import { isUsableParkedCarCoordinate } from '@parkio/validation';
import { parkingKeys } from '@/data/keys';
import { activeParkingSessionQueryOptions } from '@/data/query-options/parking';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { parkingApi } from '@/services/api';
import {
  trackParkHereFailed,
  trackParkingSessionStarted,
  trackRecentParkingRecordFailed,
} from '@/services/spaTelemetry';
import { useAuthStore } from '@/state/authStore';
import {
  recentParkingAttemptKey,
  recordRecentParkingAfterPark,
} from './recordRecentParkingAfterPark';

export const ACTIVE_PARKING_SESSION_EXISTS = 'ACTIVE_PARKING_SESSION_EXISTS';

export type ParkHereAtTargetPhase = 'idle' | 'starting' | 'success' | 'conflict' | 'error';

export type ParkHereAtTargetOutcome =
  | { status: 'success'; session: ParkingSessionResponse }
  | { status: 'conflict' }
  | { status: 'error' }
  | { status: 'busy' }
  | { status: 'offline' };

export interface ParkHereAtTargetInput {
  latitude: number;
  longitude: number;
  target?: ParkedCarTargetRef | null;
  originSurface?: SpaParkHereOriginSurface;
}

/**
 * Explicit Park Here at known coordinates (municipal / recommendation).
 */
export function useParkHereAtTarget() {
  const queryClient = useQueryClient();
  const online = useOnlineStatus();
  const userId = useAuthStore((s) => s.user?.id ?? null);
  const sessionEpoch = useAuthStore((s) => s.sessionEpoch);
  const [phase, setPhase] = useState<ParkHereAtTargetPhase>('idle');
  const inFlightRef = useRef(false);
  const recentAttemptsRef = useRef(new Set<string>());

  const reset = useCallback(() => {
    setPhase('idle');
    inFlightRef.current = false;
  }, []);

  const start = useCallback(
    async (input: ParkHereAtTargetInput): Promise<ParkHereAtTargetOutcome> => {
      if (inFlightRef.current) return { status: 'busy' };
      if (!online) {
        setPhase('error');
        trackParkHereFailed('offline', input.originSurface ?? 'unknown');
        return { status: 'offline' };
      }
      if (!isUsableParkedCarCoordinate(input.latitude, input.longitude)) {
        setPhase('error');
        trackParkHereFailed('error', input.originSurface ?? 'unknown');
        return { status: 'error' };
      }

      const startedUserId = userId;
      const startedEpoch = sessionEpoch;
      const originSurface = input.originSurface ?? 'unknown';
      inFlightRef.current = true;
      setPhase('starting');

      try {
        const session = await parkingApi.startParkingSession(
          { latitude: input.latitude, longitude: input.longitude },
          createIdempotencyKey(),
        );
        const state = useAuthStore.getState();
        if (
          (state.user?.id ?? null) !== startedUserId ||
          state.sessionEpoch !== startedEpoch
        ) {
          return { status: 'error' };
        }

        queryClient.setQueryData(parkingKeys.activeSession(), session);
        trackParkingSessionStarted(originSurface, input.target?.kind ?? null);

        if (input.target) {
          const key = recentParkingAttemptKey(session, input.target);
          if (!recentAttemptsRef.current.has(key)) {
            recentAttemptsRef.current.add(key);
            void recordRecentParkingAfterPark(queryClient, input.target).then((result) => {
              if (result === 'failed') {
                recentAttemptsRef.current.delete(key);
                trackRecentParkingRecordFailed();
              }
            });
          }
        }

        setPhase('success');
        return { status: 'success', session };
      } catch (error) {
        const state = useAuthStore.getState();
        if (
          (state.user?.id ?? null) !== startedUserId ||
          state.sessionEpoch !== startedEpoch
        ) {
          return { status: 'error' };
        }

        if (isParkioApiError(error) && error.code === ACTIVE_PARKING_SESSION_EXISTS) {
          try {
            const existing = await queryClient.fetchQuery(activeParkingSessionQueryOptions());
            if (existing) {
              queryClient.setQueryData(parkingKeys.activeSession(), existing);
            }
          } catch {
            // ignore
          }
          trackParkHereFailed('conflict', originSurface);
          setPhase('conflict');
          return { status: 'conflict' };
        }

        if (
          error instanceof ValidationError ||
          error instanceof UnauthorizedError ||
          error instanceof NetworkError ||
          error instanceof TimeoutError ||
          isParkioApiError(error)
        ) {
          trackParkHereFailed('error', originSurface);
          setPhase('error');
          return { status: 'error' };
        }

        trackParkHereFailed('error', originSurface);
        setPhase('error');
        return { status: 'error' };
      } finally {
        inFlightRef.current = false;
      }
    },
    [online, queryClient, sessionEpoch, userId],
  );

  return {
    phase,
    busy: phase === 'starting',
    start,
    reset,
  };
}
