import type {
  ParkingSessionCompletionType,
  ParkingSessionResponse,
  ParkingSource,
  ParkingSessionStatus,
} from '@parkio/types';
import { Button, EmptyState, Icon, LoadingState, SoftBadge, cn } from '@parkio/ui';
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { FriendlyApiErrorMessage } from '@/components/FriendlyApiErrorMessage';
import { isUsableParkedCoordinate } from '@/components/map/parkedCarCoords';
import { openParkingLocationInMaps } from '@/components/parking/openParkingMaps';
import { shareParkingLocation } from '@/components/parking/shareParkingLocation';
import { SettingsSectionCard } from '@/components/product/SettingsSectionCard';
import { useParkingSessionHistoryQuery } from '@/data/hooks/useParkingSessionQueries';
import {
  useDeleteParkingSessionHistoryMutation,
  useDeleteParkingSessionMutation,
} from '@/data/hooks/useParkingSessionMutations';
import { parkingKeys } from '@/data/keys';
import { isStaleParkingSessionConflict } from '@/data/parking/sessionErrors';
import { formatInstant, humanizeEnum } from '@/lib/format';
import {
  flattenTerminalHistoryPages,
  isTerminalParkingSessionStatus,
  terminalDurationParts,
} from '@/lib/parkingSessionHistoryFormat';
import { showError, showInfo, showSuccess } from '@/lib/toast';

type RowPhase = 'idle' | 'confirming-delete' | 'deleting';
type SectionDeletePhase = 'idle' | 'confirming' | 'deleting';

function statusTone(status: ParkingSessionStatus): 'primary' | 'neutral' | 'warning' {
  switch (status) {
    case 'COMPLETED':
      return 'primary';
    case 'CANCELLED':
      return 'warning';
    default:
      return 'neutral';
  }
}

function statusLabelKey(status: ParkingSessionStatus): string {
  switch (status) {
    case 'COMPLETED':
      return 'parkingHistory.statusCompleted';
    case 'CANCELLED':
      return 'parkingHistory.statusCancelled';
    case 'ACTIVE':
      return 'parkingHistory.statusActive';
    default:
      return 'parkingHistory.statusUnknown';
  }
}

function completionLabelKey(
  completionType: ParkingSessionCompletionType | null | undefined,
): string | null {
  if (completionType === 'MANUAL') return 'parkingHistory.completionManual';
  if (completionType === 'AUTO') return 'parkingHistory.completionAuto';
  return null;
}

function completionTone(
  completionType: ParkingSessionCompletionType | null | undefined,
): 'primary' | 'neutral' | 'warning' {
  return completionType === 'AUTO' ? 'warning' : 'neutral';
}

function sourceLabelKey(source: ParkingSource | string): string | null {
  switch (source) {
    case 'MANUAL':
      return 'parkingHistory.sourceManual';
    case 'COMMUNITY':
      return 'parkingHistory.sourceCommunity';
    case 'FACILITY':
      return 'parkingHistory.sourceFacility';
    case 'CURB':
      return 'parkingHistory.sourceCurb';
    case 'AUTO':
      return 'parkingHistory.sourceAuto';
    default:
      return null;
  }
}

function formatDurationLabel(
  startedAt: string,
  endedAt: string | null,
  t: (key: string, options?: Record<string, unknown>) => string,
): string | null {
  const parts = terminalDurationParts(startedAt, endedAt);
  if (!parts) return null;
  if (parts.hours <= 0 && parts.minutes <= 0) {
    return t('parkingHistory.durationUnderMinute');
  }
  if (parts.hours <= 0) {
    return t('parkingHistory.durationMinutes', { count: parts.minutes });
  }
  if (parts.minutes <= 0) {
    return t('parkingHistory.durationHours', { count: parts.hours });
  }
  return t('parkingHistory.durationHoursMinutes', {
    hours: parts.hours,
    minutes: parts.minutes,
  });
}

/**
 * Profile → Parking History: terminal ParkingSession list with open/share/delete.
 */
export function ParkingHistoryCard() {
  const { t, i18n } = useTranslation(['settings', 'common', 'map']);
  const historyQuery = useParkingSessionHistoryQuery();
  const deleteOne = useDeleteParkingSessionMutation();
  const deleteAll = useDeleteParkingSessionHistoryMutation();

  const [sectionPhase, setSectionPhase] = useState<SectionDeletePhase>('idle');
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const loadMoreInFlight = useRef(false);
  const emptyFocusRef = useRef<HTMLDivElement | null>(null);
  const sectionBusy =
    sectionPhase === 'deleting' || deleteAll.isPending || deletingId !== null;

  const items = useMemo(
    () => flattenTerminalHistoryPages(historyQuery.data?.pages),
    [historyQuery.data?.pages],
  );

  const hasEligibleHistory = items.length > 0;

  const focusAfterRowDelete = useCallback((neighborId: string | null) => {
    requestAnimationFrame(() => {
      if (neighborId) {
        const next = document.querySelector<HTMLElement>(
          `[data-history-session-id="${neighborId}"]`,
        );
        if (next) {
          next.focus();
          return;
        }
      }
      emptyFocusRef.current?.focus();
    });
  }, []);

  const loadMore = useCallback(async () => {
    if (!historyQuery.hasNextPage || historyQuery.isFetchingNextPage || loadMoreInFlight.current) {
      return;
    }
    loadMoreInFlight.current = true;
    try {
      await historyQuery.fetchNextPage();
    } finally {
      loadMoreInFlight.current = false;
    }
  }, [historyQuery]);

  const confirmDeleteAll = useCallback(async () => {
    if (sectionPhase !== 'confirming' || deleteAll.isPending || deletingId !== null) return;
    setSectionPhase('deleting');
    try {
      // PR1 delete-history invalidates sessionHistoryRoot only — active cache stays.
      await deleteAll.mutateAsync();
      showSuccess(t('parkingHistory.deleteAllSuccess'));
      setSectionPhase('idle');
      requestAnimationFrame(() => {
        emptyFocusRef.current?.focus();
      });
    } catch {
      showError(t('parkingHistory.deleteAllError'));
      setSectionPhase('idle');
    }
  }, [deleteAll, deletingId, sectionPhase, t]);

  return (
    <SettingsSectionCard
      title={t('parkingHistory.title')}
      icon="history"
      description={t('parkingHistory.description')}
      action={
        hasEligibleHistory && sectionPhase === 'idle' ? (
          <Button
            type="button"
            variant="ghost"
            className="text-on-surface-variant"
            disabled={sectionBusy}
            aria-label={t('parkingHistory.deleteAllA11y')}
            data-testid="parking-history-delete-all"
            onClick={() => setSectionPhase('confirming')}
          >
            {t('parkingHistory.deleteAll')}
          </Button>
        ) : null
      }
    >
      {sectionPhase === 'confirming' || sectionPhase === 'deleting' ? (
        <div
          className="mb-md flex flex-col gap-sm rounded-2xl bg-surface-container-low p-md"
          data-testid="parking-history-delete-all-confirm"
          role="group"
          aria-label={t('parkingHistory.deleteAllConfirmAria')}
          aria-busy={sectionBusy}
        >
          <p className="m-0 flex items-start gap-xs text-label-sm font-medium text-on-surface">
            <Icon name="warning" className="mt-px shrink-0 text-[16px] leading-none text-tertiary" />
            {t('parkingHistory.deleteAllConfirmBody')}
          </p>
          <div className="flex flex-wrap gap-sm">
            <Button
              type="button"
              variant="destructive-soft"
              className="min-w-0 flex-1"
              disabled={sectionBusy}
              aria-busy={sectionBusy}
              data-testid="parking-history-confirm-delete-all"
              onClick={() => void confirmDeleteAll()}
            >
              {sectionBusy
                ? t('parkingHistory.deleteAllBusy')
                : t('parkingHistory.deleteAllConfirmCta')}
            </Button>
            <Button
              type="button"
              variant="ghost"
              className="min-w-0 flex-1"
              disabled={sectionBusy}
              data-testid="parking-history-keep-all"
              onClick={() => setSectionPhase('idle')}
            >
              {t('parkingHistory.keepRecords')}
            </Button>
          </div>
        </div>
      ) : null}

      {historyQuery.isPending ? (
        <LoadingState label={t('parkingHistory.loading')} />
      ) : historyQuery.isError && !historyQuery.data ? (
        <div className="flex flex-col gap-sm" data-testid="parking-history-error">
          <FriendlyApiErrorMessage
            error={historyQuery.error}
            fallback={t('parkingHistory.loadError')}
          />
          <Button
            type="button"
            variant="secondary"
            className="self-start"
            onClick={() => void historyQuery.refetch()}
          >
            {t('actions.retry', { ns: 'common' })}
          </Button>
        </div>
      ) : items.length === 0 ? (
        <div
          ref={emptyFocusRef}
          tabIndex={-1}
          className="outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 rounded-2xl"
          data-testid="parking-history-empty-focus"
        >
          <EmptyState
            icon="local_parking"
            title={t('parkingHistory.emptyTitle')}
            description={t('parkingHistory.emptyDescription')}
            action={
              <Link
                to="/map"
                data-testid="parking-history-go-map"
                className="inline-flex items-center gap-xs rounded-full bg-primary px-lg py-sm text-label-md text-on-primary no-underline shadow-sm transition-all duration-std hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2"
              >
                <Icon name="map" className="text-[16px] leading-none" />
                {t('parkingHistory.goToMap')}
              </Link>
            }
          />
        </div>
      ) : (
        <div className="flex flex-col gap-sm" data-testid="parking-history-list">
          {items.map((session, index) => {
            const neighborId = items[index + 1]?.id ?? items[index - 1]?.id ?? null;
            return (
              <ParkingHistoryRow
                key={session.id}
                session={session}
                locale={i18n.language}
                neighborId={neighborId}
                disabled={sectionBusy || (deletingId !== null && deletingId !== session.id)}
                deletingId={deletingId}
                onDeletingIdChange={setDeletingId}
                onDeletedFocus={focusAfterRowDelete}
                deleteMutation={deleteOne}
              />
            );
          })}

          {historyQuery.hasNextPage ? (
            <div className="flex flex-col items-center gap-sm pt-sm">
              {historyQuery.isFetchNextPageError ? (
                <p className="m-0 text-center text-label-sm text-error" role="alert">
                  {t('parkingHistory.loadMoreError')}
                </p>
              ) : null}
              <Button
                type="button"
                variant="secondary"
                disabled={historyQuery.isFetchingNextPage}
                aria-busy={historyQuery.isFetchingNextPage}
                data-testid="parking-history-load-more"
                onClick={() => void loadMore()}
              >
                <Icon name="expand_more" className="text-[18px] leading-none" />
                {historyQuery.isFetchingNextPage
                  ? t('parkingHistory.loadingMore')
                  : t('parkingHistory.loadMore')}
              </Button>
            </div>
          ) : null}
        </div>
      )}
    </SettingsSectionCard>
  );
}

function ParkingHistoryRow({
  session,
  locale,
  neighborId,
  disabled,
  deletingId,
  onDeletingIdChange,
  onDeletedFocus,
  deleteMutation,
}: {
  session: ParkingSessionResponse;
  locale: string;
  neighborId: string | null;
  disabled: boolean;
  deletingId: string | null;
  onDeletingIdChange: (id: string | null) => void;
  onDeletedFocus: (neighborId: string | null) => void;
  deleteMutation: ReturnType<typeof useDeleteParkingSessionMutation>;
}) {
  const { t } = useTranslation(['settings', 'map', 'common']);
  const queryClient = useQueryClient();
  const [phase, setPhase] = useState<RowPhase>('idle');
  const inFlight = useRef(false);

  useEffect(() => {
    setPhase('idle');
    inFlight.current = false;
  }, [session.id]);

  const terminal = isTerminalParkingSessionStatus(session.status);
  const coordsValid = isUsableParkedCoordinate(session.latitude, session.longitude);
  const busy = phase === 'deleting' || (deletingId === session.id && deleteMutation.isPending);
  const rowDisabled = disabled || busy;
  const duration = formatDurationLabel(session.startedAt, session.endedAt, t);
  const sourceKey = sourceLabelKey(session.parkingSource);
  const completionKey = completionLabelKey(session.completionType);
  const startedLabel = formatInstant(session.startedAt, locale === 'tr' ? 'tr' : 'en');

  const openMaps = () => {
    if (!coordsValid) {
      showError(t('parkingSession.maps.invalidCoordinates', { ns: 'map' }));
      return;
    }
    if (!openParkingLocationInMaps(session.latitude, session.longitude)) {
      showError(t('parkingSession.maps.failed', { ns: 'map' }));
    }
  };

  const share = async () => {
    if (!coordsValid) {
      showError(t('parkingSession.share.invalidCoordinates', { ns: 'map' }));
      return;
    }
    const result = await shareParkingLocation({
      latitude: session.latitude,
      longitude: session.longitude,
      title: t('parkingSession.share.title', { ns: 'map' }),
      lead: t('parkingSession.share.messageLead', { ns: 'map' }),
    });
    if (result.ok) {
      if (result.method === 'clipboard') {
        showSuccess(t('parkingSession.share.copied', { ns: 'map' }));
      }
      return;
    }
    if (result.reason === 'cancelled') return;
    showError(t('parkingSession.share.failed', { ns: 'map' }));
  };

  const confirmDelete = async () => {
    if (phase !== 'confirming-delete' || inFlight.current || !terminal) return;
    inFlight.current = true;
    onDeletingIdChange(session.id);
    setPhase('deleting');
    try {
      await deleteMutation.mutateAsync(session.id);
      showSuccess(t('parkingHistory.deleteSuccess'));
      setPhase('idle');
      onDeletedFocus(neighborId);
    } catch (error) {
      if (isStaleParkingSessionConflict(error)) {
        await queryClient.invalidateQueries({ queryKey: parkingKeys.sessionHistoryRoot() });
        showInfo(t('parkingHistory.deleteAlreadyGone'));
        setPhase('idle');
        onDeletedFocus(neighborId);
      } else {
        showError(t('parkingHistory.deleteError'));
        setPhase('idle');
      }
    } finally {
      inFlight.current = false;
      onDeletingIdChange(null);
    }
  };

  return (
    <article
      data-testid="parking-history-row"
      data-session-status={session.status}
      data-history-session-id={session.id}
      tabIndex={-1}
      aria-busy={busy}
      className={cn(
        'rounded-2xl border border-outline-variant/20 bg-surface-container-lowest p-md outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2',
        busy && 'opacity-80',
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-sm">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-sm">
            <SoftBadge tone={statusTone(session.status)}>
              {t(statusLabelKey(session.status), {
                defaultValue: humanizeEnum(session.status),
              })}
            </SoftBadge>
            {session.status === 'COMPLETED' && completionKey ? (
              <SoftBadge
                tone={completionTone(session.completionType)}
                data-testid="parking-history-completion-type"
              >
                {t(completionKey)}
              </SoftBadge>
            ) : null}
            {sourceKey ? (
              <span className="text-label-sm text-on-surface-variant">{t(sourceKey)}</span>
            ) : session.parkingSource ? (
              <span className="text-label-sm text-on-surface-variant">
                {humanizeEnum(String(session.parkingSource))}
              </span>
            ) : null}
          </div>
          <p className="m-0 mt-sm text-body-md font-medium text-on-surface">{startedLabel}</p>
          {duration ? (
            <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
              {t('parkingHistory.parkedFor', { duration })}
            </p>
          ) : null}
        </div>
      </div>

      {phase === 'confirming-delete' || phase === 'deleting' ? (
        <div
          className="mt-md flex flex-col gap-sm rounded-xl bg-surface-container-low p-sm"
          data-testid="parking-history-delete-confirm"
          role="group"
          aria-label={t('parkingHistory.deleteConfirmAria')}
        >
          <p className="m-0 text-label-sm text-on-surface">{t('parkingHistory.deleteConfirmBody')}</p>
          <div className="flex flex-wrap gap-sm">
            <Button
              type="button"
              variant="destructive-soft"
              className="min-w-0 flex-1"
              disabled={busy}
              aria-busy={busy}
              data-testid="parking-history-confirm-delete"
              onClick={() => void confirmDelete()}
            >
              {busy ? t('parkingHistory.deleteBusy') : t('parkingHistory.deleteConfirmCta')}
            </Button>
            <Button
              type="button"
              variant="ghost"
              className="min-w-0 flex-1"
              disabled={busy}
              data-testid="parking-history-keep-record"
              onClick={() => setPhase('idle')}
            >
              {t('parkingHistory.keepRecord')}
            </Button>
          </div>
        </div>
      ) : (
        <div className="mt-md flex flex-wrap gap-sm">
          <Button
            type="button"
            variant="secondary"
            className="min-w-0 flex-1"
            disabled={rowDisabled || !coordsValid}
            aria-label={t('parkingHistory.openMapsA11y')}
            data-testid="parking-history-open-maps"
            onClick={openMaps}
          >
            <Icon name="map" className="text-[16px] leading-none" />
            {t('parkingHistory.openMaps')}
          </Button>
          <Button
            type="button"
            variant="secondary"
            className="min-w-0 flex-1"
            disabled={rowDisabled || !coordsValid}
            aria-label={t('parkingHistory.shareA11y')}
            data-testid="parking-history-share"
            onClick={() => void share()}
          >
            <Icon name="share" className="text-[16px] leading-none" />
            {t('parkingHistory.share')}
          </Button>
          {terminal ? (
            <Button
              type="button"
              variant="ghost"
              className="min-w-0 flex-1 text-on-surface-variant"
              disabled={rowDisabled}
              aria-label={t('parkingHistory.deleteA11y')}
              data-testid="parking-history-delete"
              onClick={() => setPhase('confirming-delete')}
            >
              {t('parkingHistory.delete')}
            </Button>
          ) : null}
        </div>
      )}
    </article>
  );
}