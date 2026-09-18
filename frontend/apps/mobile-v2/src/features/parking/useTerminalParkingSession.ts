import { useCallback, useEffect, useRef, useState } from 'react';
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
import type { ParkingSessionResponse } from '@parkio/types';
import { parkingKeys } from '@/data/keys';
import { activeParkingSessionQueryOptions } from '@/data/query-options/parking';
import { parkingApi } from '@/services/api';
import { trackParkingSessionEnded } from '@/services/spaTelemetry';
import { useAuthStore } from '@/state/authStore';

/** Stable domain codes for terminal transitions that are no longer actionable. */
export const PARKING_SESSION_NOT_ACTIVE = 'PARKING_SESSION_NOT_ACTIVE';
export const PARKING_SESSION_NOT_FOUND = 'PARKING_SESSION_NOT_FOUND';

export type TerminalParkingOp = 'complete' | 'cancel';

export type TerminalParkingPhase =
  | 'idle'
  | 'completing'
  | 'cancelling'
  | 'reconciling'
  | 'ambiguousComplete'
  | 'ambiguousCancel'
  | 'rejected';

export interface TerminalParkingControls {
  phase: TerminalParkingPhase;
  busy: boolean;
  operation: TerminalParkingOp | null;
  completeKey: string | null;
  cancelKey: string | null;
  complete: (sessionId: string) => Promise<void>;
  cancel: (sessionId: string) => Promise<void>;
  retry: () => Promise<void>;
  reset: () => void;
}

function isAmbiguousTransport(error: unknown): boolean {
  return error instanceof NetworkError || error instanceof TimeoutError;
}

function isStaleTerminalConflict(error: unknown): boolean {
  return (
    isParkioApiError(error) &&
    (error.code === PARKING_SESSION_NOT_ACTIVE || error.code === PARKING_SESSION_NOT_FOUND)
  );
}

function isConclusiveRejection(error: unknown): boolean {
  return (
    error instanceof ValidationError ||
    error instanceof UnauthorizedError ||
    (isParkioApiError(error) &&
      error.status >= 400 &&
      error.status < 500 &&
      !isStaleTerminalConflict(error))
  );
}

/**
 * Complete / cancel ParkingSession (S1-P0-04).
 * Mutual exclusion + separate caller-owned idempotency keys per operation.
 */
export function useTerminalParkingSession(sessionId: string | null): TerminalParkingControls {
  const queryClient = useQueryClient();
  const userId = useAuthStore((s) => s.user?.id ?? null);
  const sessionEpoch = useAuthStore((s) => s.sessionEpoch);

  const [phase, setPhase] = useState<TerminalParkingPhase>('idle');
  const [operation, setOperation] = useState<TerminalParkingOp | null>(null);
  const [completeKey, setCompleteKey] = useState<string | null>(null);
  const [cancelKey, setCancelKey] = useState<string | null>(null);
  const [boundIdentity, setBoundIdentity] = useState(`${userId ?? 'anon'}:${sessionEpoch}`);
  const [boundSessionId, setBoundSessionId] = useState(sessionId);

  const completeKeyRef = useRef<string | null>(null);
  const cancelKeyRef = useRef<string | null>(null);
  const operationRef = useRef<TerminalParkingOp | null>(null);
  const sessionIdRef = useRef<string | null>(sessionId);
  const inFlightRef = useRef(false);

  const identityKey = `${userId ?? 'anon'}:${sessionEpoch}`;
  if (boundIdentity !== identityKey || boundSessionId !== sessionId) {
    setBoundIdentity(identityKey);
    setBoundSessionId(sessionId);
    setCompleteKey(null);
    setCancelKey(null);
    setOperation(null);
    setPhase('idle');
  }

  useEffect(() => {
    sessionIdRef.current = sessionId;
  }, [sessionId]);

  useEffect(() => {
    completeKeyRef.current = null;
    cancelKeyRef.current = null;
    operationRef.current = null;
    inFlightRef.current = false;
  }, [identityKey, sessionId]);

  const clearAttempt = useCallback(() => {
    completeKeyRef.current = null;
    cancelKeyRef.current = null;
    operationRef.current = null;
    inFlightRef.current = false;
    setCompleteKey(null);
    setCancelKey(null);
    setOperation(null);
    setPhase('idle');
  }, []);

  const stillSameContext = useCallback(
    (startedUserId: string | null, startedEpoch: number, expectedSessionId: string) => {
      const state = useAuthStore.getState();
      return (
        (state.user?.id ?? null) === startedUserId &&
        state.sessionEpoch === startedEpoch &&
        sessionIdRef.current === expectedSessionId
      );
    },
    [],
  );

  const clearActiveCache = useCallback(() => {
    queryClient.setQueryData(parkingKeys.activeSession(), null);
  }, [queryClient]);

  const reconcileActive = useCallback(async (): Promise<ParkingSessionResponse | null> => {
    return queryClient.fetchQuery(activeParkingSessionQueryOptions());
  }, [queryClient]);

  const runTerminal = useCallback(
    async (op: TerminalParkingOp, targetSessionId: string, reuseKey: boolean) => {
      if (inFlightRef.current) {
        return;
      }
      if (sessionIdRef.current !== targetSessionId) {
        return;
      }

      const startedUserId = useAuthStore.getState().user?.id ?? null;
      const startedEpoch = useAuthStore.getState().sessionEpoch;

      let key: string;
      if (op === 'complete') {
        key = reuseKey && completeKeyRef.current ? completeKeyRef.current : createIdempotencyKey();
        completeKeyRef.current = key;
        cancelKeyRef.current = null;
        setCompleteKey(key);
        setCancelKey(null);
      } else {
        key = reuseKey && cancelKeyRef.current ? cancelKeyRef.current : createIdempotencyKey();
        cancelKeyRef.current = key;
        completeKeyRef.current = null;
        setCancelKey(key);
        setCompleteKey(null);
      }

      operationRef.current = op;
      setOperation(op);
      inFlightRef.current = true;
      setPhase(op === 'complete' ? 'completing' : 'cancelling');

      try {
        const result =
          op === 'complete'
            ? await parkingApi.completeParkingSession(targetSessionId, key)
            : await parkingApi.cancelParkingSession(targetSessionId, key);

        if (!stillSameContext(startedUserId, startedEpoch, targetSessionId)) {
          return;
        }

        // Terminal response proves session is no longer ACTIVE — set-only to avoid stale refetch race.
        if (result.status !== 'ACTIVE') {
          clearActiveCache();
          clearAttempt();
          trackParkingSessionEnded(op === 'complete' ? 'completed' : 'cancelled');
          return;
        }

        // Unexpected ACTIVE terminal payload — reconcile from server truth.
        setPhase('reconciling');
        const active = await reconcileActive();
        if (!stillSameContext(startedUserId, startedEpoch, targetSessionId)) {
          return;
        }
        if (!active || active.id !== targetSessionId) {
          if (!active) {
            clearActiveCache();
          }
          clearAttempt();
          return;
        }
        setPhase(op === 'complete' ? 'ambiguousComplete' : 'ambiguousCancel');
        inFlightRef.current = false;
      } catch (error) {
        if (!stillSameContext(startedUserId, startedEpoch, targetSessionId)) {
          return;
        }

        if (error instanceof CancellationError) {
          // Indeterminate cancel — treat like ambiguous transport: keep key, reconcile.
          setPhase('reconciling');
          try {
            const active = await reconcileActive();
            if (!stillSameContext(startedUserId, startedEpoch, targetSessionId)) {
              return;
            }
            if (!active) {
              clearActiveCache();
              clearAttempt();
              return;
            }
            if (active.id === targetSessionId) {
              setPhase(op === 'complete' ? 'ambiguousComplete' : 'ambiguousCancel');
              inFlightRef.current = false;
              return;
            }
            // Different active session — leave it; drop this attempt.
            clearAttempt();
            return;
          } catch {
            if (!stillSameContext(startedUserId, startedEpoch, targetSessionId)) {
              return;
            }
            setPhase(op === 'complete' ? 'ambiguousComplete' : 'ambiguousCancel');
            inFlightRef.current = false;
            return;
          }
        }

        if (isAmbiguousTransport(error)) {
          setPhase('reconciling');
          try {
            const active = await reconcileActive();
            if (!stillSameContext(startedUserId, startedEpoch, targetSessionId)) {
              return;
            }
            if (!active) {
              clearActiveCache();
              clearAttempt();
              return;
            }
            if (active.id === targetSessionId) {
              setPhase(op === 'complete' ? 'ambiguousComplete' : 'ambiguousCancel');
              inFlightRef.current = false;
              return;
            }
            clearAttempt();
            return;
          } catch {
            if (!stillSameContext(startedUserId, startedEpoch, targetSessionId)) {
              return;
            }
            setPhase(op === 'complete' ? 'ambiguousComplete' : 'ambiguousCancel');
            inFlightRef.current = false;
            return;
          }
        }

        if (isStaleTerminalConflict(error)) {
          setPhase('reconciling');
          try {
            const active = await reconcileActive();
            if (!stillSameContext(startedUserId, startedEpoch, targetSessionId)) {
              return;
            }
            if (!active) {
              clearActiveCache();
              clearAttempt();
              return;
            }
            if (active.id === targetSessionId) {
              // Still active despite conflict — allow retry of same op.
              setPhase(op === 'complete' ? 'ambiguousComplete' : 'ambiguousCancel');
              inFlightRef.current = false;
              return;
            }
            clearAttempt();
            return;
          } catch {
            if (!stillSameContext(startedUserId, startedEpoch, targetSessionId)) {
              return;
            }
            setPhase('rejected');
            inFlightRef.current = false;
            return;
          }
        }

        if (error instanceof UnauthorizedError) {
          clearAttempt();
          return;
        }

        if (isConclusiveRejection(error)) {
          // Drop keys so an explicit retry starts a new logical attempt.
          completeKeyRef.current = null;
          cancelKeyRef.current = null;
          setCompleteKey(null);
          setCancelKey(null);
          setPhase('rejected');
          inFlightRef.current = false;
          return;
        }

        setPhase(op === 'complete' ? 'ambiguousComplete' : 'ambiguousCancel');
        inFlightRef.current = false;
      }
    },
    [clearActiveCache, clearAttempt, reconcileActive, stillSameContext],
  );

  const complete = useCallback(
    async (targetSessionId: string) => {
      const reuse =
        operationRef.current === 'complete' &&
        Boolean(completeKeyRef.current) &&
        (phase === 'ambiguousComplete' || phase === 'rejected');
      await runTerminal('complete', targetSessionId, reuse);
    },
    [phase, runTerminal],
  );

  const cancel = useCallback(
    async (targetSessionId: string) => {
      const reuse =
        operationRef.current === 'cancel' &&
        Boolean(cancelKeyRef.current) &&
        (phase === 'ambiguousCancel' || phase === 'rejected');
      await runTerminal('cancel', targetSessionId, reuse);
    },
    [phase, runTerminal],
  );

  const retry = useCallback(async () => {
    const op = operationRef.current;
    const target = sessionIdRef.current;
    if (!op || !target) {
      return;
    }
    if (phase !== 'ambiguousComplete' && phase !== 'ambiguousCancel' && phase !== 'rejected') {
      return;
    }
    await runTerminal(op, target, true);
  }, [phase, runTerminal]);

  const busy = phase === 'completing' || phase === 'cancelling' || phase === 'reconciling';

  return {
    phase,
    busy,
    operation,
    completeKey,
    cancelKey,
    complete,
    cancel,
    retry,
    reset: clearAttempt,
  };
}