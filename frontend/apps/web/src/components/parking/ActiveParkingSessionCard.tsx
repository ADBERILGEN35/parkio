import type { ParkingSessionResponse, ParkingSource } from '@parkio/types';
import { Button, Icon, cn } from '@parkio/ui';
import { useTranslation } from 'react-i18next';
import { formatElapsedFromStartedAt } from '@/lib/parkingSessionElapsed';
import { useActiveParkingSessionActions } from './useActiveParkingSessionActions';
import { useNowTicker } from './useNowTicker';

export interface ActiveParkingSessionCardProps {
  session: ParkingSessionResponse;
  onFocusCar: () => void;
  className?: string;
}

function sourceLabelKey(source: ParkingSource): string | null {
  switch (source) {
    case 'MANUAL':
      return 'parkingSession.sourceManual';
    case 'COMMUNITY':
      return 'parkingSession.sourceCommunity';
    case 'FACILITY':
      return 'parkingSession.sourceFacility';
    case 'CURB':
      return 'parkingSession.sourceCurb';
    case 'AUTO':
      return 'parkingSession.sourceAuto';
    default:
      return null;
  }
}

/**
 * Persistent Active Parking Session map chrome (PR2 + PR4).
 * Find my car (in-app focus), Open in Maps, Share, complete, and cancel.
 */
export function ActiveParkingSessionCard({
  session,
  onFocusCar,
  className,
}: ActiveParkingSessionCardProps) {
  const { t } = useTranslation('map');
  const now = useNowTicker(session.status === 'ACTIVE');
  const elapsed = formatElapsedFromStartedAt(session.startedAt, now);
  const sourceKey = sourceLabelKey(session.parkingSource);
  const actions = useActiveParkingSessionActions({ session, onFocusCar });

  const showCompleteConfirm =
    actions.phase === 'confirming-complete' || actions.phase === 'completing';
  const showCancelConfirm =
    actions.phase === 'confirming-cancel' || actions.phase === 'cancelling';
  const busy = actions.terminalBusy;

  return (
    <div
      role="region"
      aria-label={t('parkingSession.activeAria', { duration: elapsed })}
      aria-busy={busy}
      data-testid="active-parking-session-card"
      className={cn(
        'pointer-events-auto animate-fade-in-up rounded-3xl glass-panel p-md shadow-deep ring-1 ring-primary/10',
        className,
      )}
    >
      <div className="flex items-start gap-md">
        <span
          aria-hidden
          className="relative flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl bg-primary text-on-primary shadow-md"
        >
          <Icon name="directions_car" className="text-[28px] leading-none" filled />
        </span>

        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-sm">
            <p className="m-0 text-body-md font-semibold text-on-surface">
              {t('parkingSession.activeTitle')}
            </p>
            <span className="inline-flex shrink-0 items-center rounded-full bg-primary/10 px-sm py-xs text-label-sm font-semibold text-primary">
              {t('parkingSession.activeStatus')}
            </span>
          </div>

          <p
            className="m-0 mt-xs text-label-sm font-medium tabular-nums text-on-surface-variant"
            data-testid="active-parking-elapsed"
            aria-label={t('parkingSession.elapsedAria', { duration: elapsed })}
          >
            {t('parkingSession.elapsed', { duration: elapsed })}
          </p>

          {sourceKey ? (
            <p
              className="m-0 mt-xs text-label-sm text-on-surface-variant"
              data-testid="active-parking-source"
            >
              {t(sourceKey)}
            </p>
          ) : null}
        </div>
      </div>

      <div className="mt-md flex flex-col gap-sm">
        <Button
          type="button"
          className="w-full"
          onClick={actions.findMyCar}
          disabled={busy || !actions.destinationValid}
          aria-label={t('parkingSession.findMyCarAria')}
          data-testid="active-parking-find-my-car"
        >
          <Icon name="near_me" className="text-[18px] leading-none" />
          {t('parkingSession.findMyCar')}
        </Button>

        {!showCompleteConfirm && !showCancelConfirm ? (
          <div className="flex flex-wrap gap-sm">
            <Button
              type="button"
              variant="secondary"
              className="min-w-0 flex-1"
              onClick={actions.openInMaps}
              disabled={actions.mapsDisabled}
              aria-label={t('parkingSession.maps.a11y')}
              data-testid="active-parking-open-maps"
            >
              <Icon name="map" className="text-[18px] leading-none" />
              {t('parkingSession.maps.cta')}
            </Button>
            <Button
              type="button"
              variant="secondary"
              className="min-w-0 flex-1"
              onClick={() => void actions.share()}
              disabled={actions.shareDisabled}
              aria-label={t('parkingSession.share.a11y')}
              data-testid="active-parking-share"
            >
              <Icon name="share" className="text-[18px] leading-none" />
              {t('parkingSession.share.cta')}
            </Button>
          </div>
        ) : null}

        {showCompleteConfirm ? (
          <div
            className="flex flex-col gap-sm rounded-2xl bg-surface-container-low p-md"
            data-testid="active-parking-complete-confirm"
            role="group"
            aria-label={t('parkingSession.complete.confirmAria')}
          >
            <p className="m-0 text-label-sm font-medium text-on-surface">
              {t('parkingSession.complete.confirmBody')}
            </p>
            <div className="flex flex-wrap gap-sm">
              <Button
                type="button"
                className="min-w-0 flex-1"
                onClick={() => void actions.confirmComplete()}
                disabled={busy}
                aria-busy={busy}
                data-testid="active-parking-confirm-leave"
              >
                {busy ? t('parkingSession.complete.busy') : t('parkingSession.complete.confirmCta')}
              </Button>
              <Button
                type="button"
                variant="ghost"
                className="min-w-0 flex-1"
                onClick={actions.keepSession}
                disabled={busy}
                data-testid="active-parking-keep-session"
              >
                {t('parkingSession.keepSession')}
              </Button>
            </div>
          </div>
        ) : showCancelConfirm ? null : (
          <Button
            type="button"
            variant="outline"
            className="w-full"
            onClick={actions.beginComplete}
            disabled={busy}
            aria-expanded={false}
            aria-label={t('parkingSession.complete.a11y')}
            data-testid="active-parking-leave"
          >
            {t('parkingSession.complete.cta')}
          </Button>
        )}

        {showCancelConfirm ? (
          <div
            className="flex flex-col gap-sm rounded-2xl bg-surface-container-low p-md"
            data-testid="active-parking-cancel-confirm"
            role="group"
            aria-label={t('parkingSession.cancel.confirmAria')}
          >
            <p className="m-0 flex items-start gap-xs text-label-sm font-medium text-on-surface">
              <Icon
                name="warning"
                className="mt-px shrink-0 text-[16px] leading-none text-tertiary"
              />
              {t('parkingSession.cancel.confirmBody')}
            </p>
            <div className="flex flex-wrap gap-sm">
              <Button
                type="button"
                variant="secondary"
                className="min-w-0 flex-1"
                onClick={() => void actions.confirmCancel()}
                disabled={busy}
                aria-busy={busy}
                data-testid="active-parking-confirm-cancel"
              >
                {busy ? t('parkingSession.cancel.busy') : t('parkingSession.cancel.confirmCta')}
              </Button>
              <Button
                type="button"
                variant="ghost"
                className="min-w-0 flex-1"
                onClick={actions.keepSession}
                disabled={busy}
                data-testid="active-parking-keep-session"
              >
                {t('parkingSession.keepSession')}
              </Button>
            </div>
          </div>
        ) : showCompleteConfirm ? null : (
          <Button
            type="button"
            variant="ghost"
            className="w-full text-on-surface-variant"
            onClick={actions.beginCancel}
            disabled={busy}
            aria-expanded={false}
            aria-label={t('parkingSession.cancel.a11y')}
            data-testid="active-parking-cancel"
          >
            {t('parkingSession.cancel.cta')}
          </Button>
        )}
      </div>
    </div>
  );
}

/** Compact recoverable error strip — no toast loops. */
export function ActiveParkingSessionErrorCard({
  onRetry,
  className,
}: {
  onRetry: () => void;
  className?: string;
}) {
  const { t } = useTranslation(['map', 'common']);

  return (
    <div
      role="alert"
      data-testid="active-parking-session-error"
      className={cn(
        'pointer-events-auto animate-fade-in-up rounded-3xl glass-panel p-md shadow-deep',
        className,
      )}
    >
      <div className="flex items-center gap-md">
        <span
          aria-hidden
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-surface-container text-on-surface-variant"
        >
          <Icon name="directions_car" className="text-[22px] leading-none" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="m-0 text-body-md font-semibold text-on-surface">{t('parkingSession.error')}</p>
        </div>
        <Button type="button" variant="secondary" onClick={onRetry} className="shrink-0">
          {t('actions.retry', { ns: 'common' })}
        </Button>
      </div>
    </div>
  );
}