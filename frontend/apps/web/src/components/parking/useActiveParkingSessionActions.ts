import { UnauthorizedError, isParkioApiError } from '@parkio/api-client';
import type { ParkingSessionResponse } from '@parkio/types';
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { isUsableParkedCoordinate } from '@/components/map/parkedCarCoords';
import {
  useCancelParkingSessionMutation,
  useCompleteParkingSessionMutation,
  useConfirmActiveParkingSessionMutation,
} from '@/data/hooks/useParkingSessionMutations';
import { setActiveParkingSession } from '@/data/parking/sessionCache';
import {
  isAmbiguousParkingTransport,
  isStaleParkingSessionConflict,
} from '@/data/parking/sessionErrors';
import { activeParkingSessionQueryOptions } from '@/data/query-options/parking';
import { useParkingSessionLifecycleConfigQuery } from '@/data/hooks/useParkingSessionQueries';
import { needsActiveConfirmation } from '@/lib/parkingSessionStale';
import { showError, showInfo, showSuccess } from '@/lib/toast';
import { trackParkingSessionEnded, trackReturnToCarStarted } from '@/services/spaTelemetry';
import { openParkingLocationInMaps } from './openParkingMaps';
import { shareParkingLocation } from './shareParkingLocation';
import { useNowTicker } from './useNowTicker';

/**
 * Local Active-card action phase. Mutation pending is also reflected here so the
 * UI can disable terminal controls without scattered booleans.
 */
export type ActiveParkingActionPhase =
  | 'idle'
  | 'confirming-complete'
  | 'confirming-cancel'
  | 'confirming-stale-leave'
  | 'completing'
  | 'cancelling'
  | 'confirming-active';

export interface ActiveParkingSessionActions {
  phase: ActiveParkingActionPhase;
  terminalBusy: boolean;
  destinationValid: boolean;
  mapsDisabled: boolean;
  shareDisabled: boolean;
  /** True when the configured confirm-after window requires still-parked confirmation. */
  requiresActiveConfirmation: boolean;
  findMyCar: () => void;
  openInMaps: () => void;
  share: () => Promise<void>;
  beginComplete: () => void;
  beginCancel: () => void;
  keepSession: () => void;
  confirmComplete: () => Promise<void>;
  confirmCancel: () => Promise<void>;
  confirmStillParked: () => Promise<void>;
  confirmAlreadyLeft: () => void;
}

/**
 * Find / Open in Maps / Share / Complete / Cancel / stale confirm for an ACTIVE
 * Parking Session. Uses PR1 mutation hooks only — never calls the SDK from UI.
 */
export function useActiveParkingSessionActions(options: {
  session: ParkingSessionResponse;
  onFocusCar: () => void;
}): ActiveParkingSessionActions {
  const { session, onFocusCar } = options;
  const { t } = useTranslation('map');
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  const completeMutation = useCompleteParkingSessionMutation();
  const cancelMutation = useCancelParkingSessionMutation();
  const confirmActiveMutation = useConfirmActiveParkingSessionMutation();
  const lifecycleConfigQuery = useParkingSessionLifecycleConfigQuery();
  const nowMs = useNowTicker(session.status === 'ACTIVE');

  const [phase, setPhase] = useState<ActiveParkingActionPhase>('idle');
  const phaseRef = useRef(phase);
  const inFlightRef = useRef(false);
  const sessionIdRef = useRef(session.id);

  useEffect(() => {
    phaseRef.current = phase;
  }, [phase]);

  // Reset confirmation when the active session identity changes or disappears.
  useEffect(() => {
    if (sessionIdRef.current !== session.id) {
      sessionIdRef.current = session.id;
      setPhase('idle');
      inFlightRef.current = false;
    }
  }, [session.id]);

  const confirmAfterMs = lifecycleConfigQuery.data?.confirmAfterMs;
  const requiresActiveConfirmation =
    confirmAfterMs != null && needsActiveConfirmation(session, confirmAfterMs, nowMs);
  const destinationValid = isUsableParkedCoordinate(session.latitude, session.longitude);
  const terminalBusy =
    phase === 'completing' ||
    phase === 'cancelling' ||
    phase === 'confirming-active';
  const mapsDisabled = terminalBusy || !destinationValid || requiresActiveConfirmation;
  const shareDisabled = terminalBusy || !destinationValid || requiresActiveConfirmation;

  const keepSession = useCallback(() => {
    if (
      phaseRef.current === 'completing' ||
      phaseRef.current === 'cancelling' ||
      phaseRef.current === 'confirming-active'
    ) {
      return;
    }
    setPhase('idle');
  }, []);

  const beginComplete = useCallback(() => {
    if (inFlightRef.current) return;
    if (
      phaseRef.current === 'completing' ||
      phaseRef.current === 'cancelling' ||
      phaseRef.current === 'confirming-active' ||
      phaseRef.current === 'confirming-stale-leave'
    ) {
      return;
    }
    setPhase('confirming-complete');
  }, []);

  const beginCancel = useCallback(() => {
    if (inFlightRef.current) return;
    if (
      phaseRef.current === 'completing' ||
      phaseRef.current === 'cancelling' ||
      phaseRef.current === 'confirming-active'
    ) {
      return;
    }
    setPhase('confirming-cancel');
  }, []);

  const findMyCar = useCallback(() => {
    if (terminalBusy || requiresActiveConfirmation) return;
    trackReturnToCarStarted();
    onFocusCar();
  }, [onFocusCar, requiresActiveConfirmation, terminalBusy]);

  const openInMaps = useCallback(() => {
    if (mapsDisabled) {
      if (requiresActiveConfirmation) {
        showInfo(t('parkingSession.stale.requiredBeforeFind'));
        return;
      }
      if (!destinationValid) {
        showError(t('parkingSession.maps.invalidCoordinates'));
      }
      return;
    }
    trackReturnToCarStarted();
    const opened = openParkingLocationInMaps(session.latitude, session.longitude);
    if (!opened) {
      showError(t('parkingSession.maps.failed'));
    }
  }, [
    destinationValid,
    mapsDisabled,
    requiresActiveConfirmation,
    session.latitude,
    session.longitude,
    t,
  ]);

  const share = useCallback(async () => {
    if (shareDisabled) {
      if (requiresActiveConfirmation) {
        showInfo(t('parkingSession.stale.requiredBeforeFind'));
        return;
      }
      if (!destinationValid) {
        showError(t('parkingSession.share.invalidCoordinates'));
      }
      return;
    }
    const result = await shareParkingLocation({
      latitude: session.latitude,
      longitude: session.longitude,
      title: t('parkingSession.share.title'),
      lead: t('parkingSession.share.messageLead'),
    });
    if (result.ok) {
      if (result.method === 'clipboard') {
        showSuccess(t('parkingSession.share.copied'));
      }
      return;
    }
    if (result.reason === 'cancelled') return;
    if (result.reason === 'invalid_destination') {
      showError(t('parkingSession.share.invalidCoordinates'));
      return;
    }
    showError(t('parkingSession.share.failed'));
  }, [
    destinationValid,
    requiresActiveConfirmation,
    session.latitude,
    session.longitude,
    shareDisabled,
    t,
  ]);

  const reconcileAfterTerminal = useCallback(
    async (
      targetSessionId: string,
      kind: 'stale' | 'ambiguous',
    ): Promise<'cleared' | 'still-active' | 'unknown'> => {
      try {
        const active = await queryClient.fetchQuery(activeParkingSessionQueryOptions(sdk));
        if (!active) {
          setActiveParkingSession(queryClient, null);
          return 'cleared';
        }
        if (active.id !== targetSessionId) {
          // A different ACTIVE session won — leave server truth alone.
          return 'cleared';
        }
        return 'still-active';
      } catch {
        if (kind === 'ambiguous') {
          return 'unknown';
        }
        // Stale conflict but refetch failed — drop local active to avoid zombie UI.
        setActiveParkingSession(queryClient, null);
        return 'cleared';
      }
    },
    [queryClient, sdk],
  );

  const runTerminal = useCallback(
    async (op: 'complete' | 'cancel') => {
      if (inFlightRef.current) return;
      if (sessionIdRef.current !== session.id) return;

      inFlightRef.current = true;
      setPhase(op === 'complete' ? 'completing' : 'cancelling');
      const targetId = session.id;

      try {
        const result =
          op === 'complete'
            ? await completeMutation.mutateAsync(targetId)
            : await cancelMutation.mutateAsync(targetId);

        if (sessionIdRef.current !== targetId) {
          return;
        }

        if (result.status === 'ACTIVE') {
          // PR1 safety: unexpected ACTIVE payload preserves active UI.
          showInfo(t('parkingSession.terminal.stillActive'));
          setPhase('idle');
          return;
        }

        showSuccess(
          op === 'complete'
            ? t('parkingSession.complete.success')
            : t('parkingSession.cancel.success'),
        );
        trackParkingSessionEnded(op === 'complete' ? 'completed' : 'cancelled');
        setPhase('idle');
      } catch (error) {
        if (sessionIdRef.current !== targetId) {
          return;
        }

        if (error instanceof UnauthorizedError) {
          setPhase('idle');
          return;
        }

        if (isStaleParkingSessionConflict(error)) {
          const outcome = await reconcileAfterTerminal(targetId, 'stale');
          if (sessionIdRef.current !== targetId) return;
          if (outcome === 'cleared') {
            showInfo(t('parkingSession.terminal.alreadyEnded'));
            setPhase('idle');
            return;
          }
          showInfo(t('parkingSession.terminal.stillActive'));
          setPhase('idle');
          return;
        }

        if (isAmbiguousParkingTransport(error)) {
          const outcome = await reconcileAfterTerminal(targetId, 'ambiguous');
          if (sessionIdRef.current !== targetId) return;
          if (outcome === 'cleared') {
            showSuccess(
              op === 'complete'
                ? t('parkingSession.complete.success')
                : t('parkingSession.cancel.success'),
            );
            setPhase('idle');
            return;
          }
          if (outcome === 'still-active') {
            showInfo(t('parkingSession.terminal.ambiguousStillActive'));
            setPhase('idle');
            return;
          }
          showInfo(t('parkingSession.terminal.ambiguousUnknown'));
          setPhase('idle');
          return;
        }

        if (isParkioApiError(error) && error.status >= 500) {
          showError(t('parkingSession.terminal.serverError'));
          setPhase('idle');
          return;
        }

        showError(
          isParkioApiError(error)
            ? error.message
            : t('parkingSession.terminal.networkError'),
        );
        setPhase('idle');
      } finally {
        inFlightRef.current = false;
      }
    },
    [cancelMutation, completeMutation, reconcileAfterTerminal, session.id, t],
  );

  const confirmComplete = useCallback(async () => {
    if (
      phaseRef.current !== 'confirming-complete' &&
      phaseRef.current !== 'confirming-stale-leave'
    ) {
      return;
    }
    await runTerminal('complete');
  }, [runTerminal]);

  const confirmCancel = useCallback(async () => {
    if (phaseRef.current !== 'confirming-cancel') return;
    await runTerminal('cancel');
  }, [runTerminal]);

  const confirmStillParked = useCallback(async () => {
    if (inFlightRef.current) return;
    if (sessionIdRef.current !== session.id) return;
    if (phaseRef.current === 'confirming-stale-leave') return;

    inFlightRef.current = true;
    setPhase('confirming-active');
    const targetId = session.id;

    try {
      const result = await confirmActiveMutation.mutateAsync(targetId);
      if (sessionIdRef.current !== targetId) return;

      if (result.status !== 'ACTIVE') {
        showInfo(t('parkingSession.terminal.alreadyEnded'));
        setPhase('idle');
        return;
      }

      showSuccess(t('parkingSession.stale.confirmSuccess'));
      setPhase('idle');
    } catch (error) {
      if (sessionIdRef.current !== targetId) return;

      if (error instanceof UnauthorizedError) {
        setPhase('idle');
        return;
      }

      if (isStaleParkingSessionConflict(error)) {
        const outcome = await reconcileAfterTerminal(targetId, 'stale');
        if (sessionIdRef.current !== targetId) return;
        if (outcome === 'cleared') {
          showInfo(t('parkingSession.terminal.alreadyEnded'));
          setPhase('idle');
          return;
        }
        showInfo(t('parkingSession.terminal.stillActive'));
        setPhase('idle');
        return;
      }

      if (isParkioApiError(error) && error.status >= 500) {
        showError(t('parkingSession.terminal.serverError'));
        setPhase('idle');
        return;
      }

      showError(
        isParkioApiError(error) ? error.message : t('parkingSession.terminal.networkError'),
      );
      setPhase('idle');
    } finally {
      inFlightRef.current = false;
    }
  }, [confirmActiveMutation, reconcileAfterTerminal, session.id, t]);

  const confirmAlreadyLeft = useCallback(() => {
    if (inFlightRef.current) return;
    if (
      phaseRef.current === 'completing' ||
      phaseRef.current === 'cancelling' ||
      phaseRef.current === 'confirming-active'
    ) {
      return;
    }
    setPhase('confirming-stale-leave');
  }, []);

  return {
    phase,
    terminalBusy,
    destinationValid,
    mapsDisabled,
    shareDisabled,
    requiresActiveConfirmation,
    findMyCar,
    openInMaps,
    share,
    beginComplete,
    beginCancel,
    keepSession,
    confirmComplete,
    confirmCancel,
    confirmStillParked,
    confirmAlreadyLeft,
  };
}
