import {
  NetworkError,
  TimeoutError,
  UnauthorizedError,
  isParkioApiError,
} from '@parkio/api-client';
import type { ParkedCarTargetRef, ParkingSessionResponse } from '@parkio/types';
import { isUsableParkedCarCoordinate } from '@parkio/validation';
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useRef, useState } from 'react';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { useAuthStoreApi } from '@/auth/store';
import { useStartParkingSessionMutation } from '@/data/hooks/useParkingSessionMutations';
import { isActiveParkingSessionConflict } from '@/data/parking/sessionErrors';
import {
  recentParkingAttemptKey,
  recordRecentParkingAfterPark,
} from '@/data/parking/recordRecentParkingAfterPark';
import { activeParkingSessionQueryOptions } from '@/data/query-options/parking';
import { recordOutcomeForSelectedCandidate } from '@/services/rankingEvaluationCorrelation';
import {
  trackParkHereFailed,
  trackParkingSessionStarted,
  trackRecentParkingRecordFailed,
} from '@/services/spaTelemetry';
import type { SpaParkHereOriginSurface } from '@parkio/types';

export type ParkHereAtTargetPhase =
  | 'idle'
  | 'starting'
  | 'success'
  | 'conflict'
  | 'error';

export type ParkHereAtTargetOutcome =
  | { status: 'success'; session: ParkingSessionResponse }
  | { status: 'conflict' }
  | { status: 'error' }
  | { status: 'busy' };

export interface ParkHereAtTargetInput {
  latitude: number;
  longitude: number;
  /** When set and municipal, RecentParking is recorded after success (fail-open). */
  target?: ParkedCarTargetRef | null;
  originSurface?: SpaParkHereOriginSurface;
}

export interface ParkHereAtTargetControls {
  phase: ParkHereAtTargetPhase;
  busy: boolean;
  start: (input: ParkHereAtTargetInput) => Promise<ParkHereAtTargetOutcome>;
  reset: () => void;
}

/**
 * Explicit “Park Here” at known coordinates (municipal / recommendation).
 * Does not acquire geolocation — caller supplies facility/candidate coords.
 */
export function useParkHereAtTarget(): ParkHereAtTargetControls {
  const sdk = useParkioSdk();
  const authStore = useAuthStoreApi();
  const queryClient = useQueryClient();
  const startMutation = useStartParkingSessionMutation();
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
      if (!authStore.getState().isAuthenticated) {
        setPhase('error');
        return { status: 'error' };
      }
      if (!isUsableParkedCarCoordinate(input.latitude, input.longitude)) {
        setPhase('error');
        return { status: 'error' };
      }

      inFlightRef.current = true;
      setPhase('starting');
      const originSurface = input.originSurface ?? 'unknown';
      try {
        const session = await startMutation.mutateAsync({
          latitude: input.latitude,
          longitude: input.longitude,
        });

        trackParkingSessionStarted(originSurface, input.target?.kind ?? null);
        if (originSurface === 'recommendation') {
          recordOutcomeForSelectedCandidate('PARKING_SESSION_STARTED', 'WEB');
        }

        if (input.target) {
          const key = recentParkingAttemptKey(session, input.target);
          if (!recentAttemptsRef.current.has(key)) {
            recentAttemptsRef.current.add(key);
            void recordRecentParkingAfterPark(sdk, queryClient, input.target).then((result) => {
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
        if (isActiveParkingSessionConflict(error)) {
          try {
            await queryClient.fetchQuery(activeParkingSessionQueryOptions(sdk));
          } catch {
            // ignore
          }
          trackParkHereFailed('conflict', originSurface);
          setPhase('conflict');
          return { status: 'conflict' };
        }

        void error;
        if (
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
    [authStore, queryClient, sdk, startMutation],
  );

  return {
    phase,
    busy: phase === 'starting' || startMutation.isPending,
    start,
    reset,
  };
}
