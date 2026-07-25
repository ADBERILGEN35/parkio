import {
  NetworkError,
  TimeoutError,
  UnauthorizedError,
  isParkioApiError,
} from '@parkio/api-client';
import { Button, Icon, cn } from '@parkio/ui';
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { useAuthStoreApi } from '@/auth/store';
import { isUsableParkedCoordinate } from '@/components/map/parkedCarCoords';
import { useStartParkingSessionMutation } from '@/data/hooks/useParkingSessionMutations';
import { isActiveParkingSessionConflict } from '@/data/parking/sessionErrors';
import { activeParkingSessionQueryOptions } from '@/data/query-options/parking';
import { showError, showInfo, showSuccess } from '@/lib/toast';
import {
  acquireBrowserPosition,
  type BrowserGeolocationFailure,
} from './acquireBrowserPosition';

export type ParkHerePhase = 'idle' | 'locating' | 'saving';

export interface ParkHereStartControlProps {
  className?: string;
}

/**
 * Map chrome "Park Here" entry for manual Parking Session start (PR3).
 * Hidden by the parent while ACTIVE exists or the active query is still pending.
 */
export function ParkHereStartControl({ className }: ParkHereStartControlProps) {
  const { t } = useTranslation(['map', 'common', 'errors']);
  const sdk = useParkioSdk();
  const authStore = useAuthStoreApi();
  const queryClient = useQueryClient();
  const startMutation = useStartParkingSessionMutation();
  const [phase, setPhase] = useState<ParkHerePhase>('idle');
  const inFlightRef = useRef(false);

  const busy = phase === 'locating' || phase === 'saving';

  const geoMessage = useCallback(
    (reason: BrowserGeolocationFailure): string => {
      switch (reason) {
        case 'unsupported':
          return t('parkingSession.start.geoUnsupported');
        case 'denied':
          return t('parkingSession.start.geoDenied');
        case 'timeout':
          return t('parkingSession.start.geoTimeout');
        case 'unavailable':
        default:
          return t('parkingSession.start.geoUnavailable');
      }
    },
    [t],
  );

  const start = useCallback(async () => {
    if (inFlightRef.current) return;
    inFlightRef.current = true;

    try {
      setPhase('locating');
      const position = await acquireBrowserPosition();
      // Geolocation can resolve after logout — never write private session data then.
      if (!authStore.getState().isAuthenticated) {
        setPhase('idle');
        return;
      }
      if (!position.ok) {
        showError(geoMessage(position.reason));
        setPhase('idle');
        return;
      }

      if (!isUsableParkedCoordinate(position.latitude, position.longitude)) {
        showError(t('parkingSession.start.invalidCoordinates'));
        setPhase('idle');
        return;
      }

      if (!authStore.getState().isAuthenticated) {
        setPhase('idle');
        return;
      }

      setPhase('saving');
      try {
        await startMutation.mutateAsync({
          latitude: position.latitude,
          longitude: position.longitude,
        });
        showSuccess(t('parkingSession.start.success'));
        setPhase('idle');
      } catch (error) {
        if (isActiveParkingSessionConflict(error)) {
          try {
            await queryClient.fetchQuery(activeParkingSessionQueryOptions(sdk));
          } catch {
            // Cache may still be empty; toast still explains the conflict.
          }
          showInfo(t('parkingSession.start.alreadyActive'));
          setPhase('idle');
          return;
        }

        if (error instanceof UnauthorizedError) {
          showError(t('parkingSession.start.unauthorized'));
          setPhase('idle');
          return;
        }

        if (error instanceof NetworkError || error instanceof TimeoutError) {
          showError(t('parkingSession.start.networkError'));
          setPhase('idle');
          return;
        }

        if (isParkioApiError(error) && error.status >= 500) {
          showError(t('parkingSession.start.serverError'));
          setPhase('idle');
          return;
        }

        showError(
          isParkioApiError(error)
            ? error.message
            : t('parkingSession.start.networkError'),
        );
        setPhase('idle');
      }
    } finally {
      inFlightRef.current = false;
    }
  }, [authStore, geoMessage, queryClient, sdk, startMutation, t]);

  const label =
    phase === 'locating'
      ? t('parkingSession.start.locating')
      : phase === 'saving'
        ? t('parkingSession.start.saving')
        : t('parkingSession.start.cta');

  return (
    <div
      role="region"
      aria-label={t('parkingSession.start.regionAria')}
      data-testid="park-here-start"
      className={cn(
        'pointer-events-auto animate-fade-in-up rounded-3xl glass-panel p-md shadow-deep ring-1 ring-primary/10',
        className,
      )}
    >
      <div className="flex items-start gap-md">
        <span
          aria-hidden
          className="relative flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl bg-primary/10 text-primary shadow-sm"
        >
          <Icon name="directions_car" className="text-[28px] leading-none" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="m-0 text-body-md font-semibold text-on-surface">
            {t('parkingSession.start.title')}
          </p>
          <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
            {t('parkingSession.start.lead')}
          </p>
        </div>
      </div>

      <Button
        type="button"
        className="mt-md w-full"
        disabled={busy}
        aria-busy={busy}
        aria-label={t('parkingSession.start.a11y')}
        data-testid="park-here-cta"
        onClick={() => void start()}
      >
        <Icon
          name={busy ? 'progress_activity' : 'local_parking'}
          className={cn('text-[18px] leading-none', busy && 'animate-spin')}
        />
        {label}
      </Button>
    </div>
  );
}