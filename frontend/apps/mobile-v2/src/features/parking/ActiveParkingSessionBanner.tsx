import { useCallback, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { isParkioApiError } from '@parkio/api-client';
import { needsActiveConfirmation } from '@parkio/validation';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { ConfirmModal } from '@/components/ui/ConfirmModal';
import { Glass } from '@/components/ui/Glass';
import { IconButton } from '@/components/ui/IconButton';
import { Skeleton } from '@/components/ui/Skeleton';
import { parkingKeys } from '@/data/keys';
import {
  activeParkingSessionQueryOptions,
  parkingSessionLifecycleConfigQueryOptions,
} from '@/data/query-options/parking';
import { useAuthStore } from '@/state/authStore';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';
import { parkingApi } from '@/services/api';
import { formatElapsedFromStartedAt } from './elapsedDuration';
import { useActiveParkingSession } from './useActiveParkingSession';
import { useNowTicker } from './useNowTicker';
import { useParkingLocationActions } from './useParkingLocationActions';
import {
  PARKING_SESSION_NOT_ACTIVE,
  PARKING_SESSION_NOT_FOUND,
  useTerminalParkingSession,
  type TerminalParkingPhase,
} from './useTerminalParkingSession';

/**
 * Map chrome strip for the ACTIVE ParkingSession.
 * Parity with web: stale confirmation, second leave confirm, location actions.
 */
export function ActiveParkingSessionBanner() {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const queryClient = useQueryClient();
  const userId = useAuthStore((s) => s.user?.id ?? null);
  const query = useActiveParkingSession();
  const lifecycleConfig = useQuery({
    ...parkingSessionLifecycleConfigQueryOptions(),
    enabled: Boolean(userId),
  });
  const sessionId = query.data?.id ?? null;
  const terminal = useTerminalParkingSession(sessionId);
  const [cancelConfirmOpen, setCancelConfirmOpen] = useState(false);
  const [completeConfirmOpen, setCompleteConfirmOpen] = useState(false);
  const [staleLeaveConfirmOpen, setStaleLeaveConfirmOpen] = useState(false);
  const [confirmingActive, setConfirmingActive] = useState(false);

  const locationActions = useParkingLocationActions({
    sessionId,
    latitude: query.data?.latitude,
    longitude: query.data?.longitude,
    terminalBusy: terminal.busy || confirmingActive,
  });

  const tickerEnabled = Boolean(query.data) && query.data?.status === 'ACTIVE';
  const now = useNowTicker(tickerEnabled);
  const confirmAfterMs = lifecycleConfig.data?.confirmAfterMs;
  const requiresActiveConfirmation = Boolean(
    query.data &&
      confirmAfterMs != null &&
      needsActiveConfirmation(query.data, confirmAfterMs, now),
  );

  const confirmStillParked = useCallback(async () => {
    if (!query.data || confirmingActive || terminal.busy) return;
    setConfirmingActive(true);
    try {
      const updated = await parkingApi.confirmActiveParkingSession(query.data.id);
      queryClient.setQueryData(parkingKeys.activeSession(), updated);
      await queryClient.invalidateQueries({ queryKey: activeParkingSessionQueryOptions().queryKey });
    } catch (error) {
      if (
        isParkioApiError(error) &&
        (error.code === PARKING_SESSION_NOT_ACTIVE || error.code === PARKING_SESSION_NOT_FOUND)
      ) {
        queryClient.setQueryData(parkingKeys.activeSession(), null);
        await queryClient.invalidateQueries({ queryKey: activeParkingSessionQueryOptions().queryKey });
      }
    } finally {
      setConfirmingActive(false);
    }
  }, [confirmingActive, query.data, queryClient, terminal.busy]);

  if (query.isPending) {
    return (
      <Glass radius={16}>
        <View
          style={styles.row}
          accessibilityRole="progressbar"
          accessibilityLabel={t('parkingSession.loading')}
        >
          <Skeleton width={34} height={34} radius={17} />
          <View style={styles.labels}>
            <Skeleton height={14} radius={4} />
            <Skeleton height={12} radius={4} style={styles.leadSkeleton} />
          </View>
        </View>
      </Glass>
    );
  }

  if (query.isError) {
    return (
      <Glass radius={16}>
        <View
          style={styles.row}
          accessibilityRole="summary"
          accessibilityLabel={t('parkingSession.error')}
        >
          <View style={[styles.iconBubble, { backgroundColor: colors.surfaceContainer2 }]}>
            <MaterialCommunityIcons name="car-off" size={18} color={colors.tertiary} />
          </View>
          <View style={styles.labels}>
            <AppText variant="bodySm" numberOfLines={1} style={styles.title}>
              {t('parkingSession.error')}
            </AppText>
            <AppText variant="labelSm" color={colors.onSurfaceVariant} numberOfLines={1}>
              {t('common.error.network')}
            </AppText>
          </View>
          <Button
            label={t('common.retry')}
            size="sm"
            variant="tonal"
            onPress={() => void query.refetch()}
          />
        </View>
      </Glass>
    );
  }

  if (!query.data) {
    return null;
  }

  const session = query.data;
  const elapsed = formatElapsedFromStartedAt(session.startedAt, now);
  const busy = terminal.busy || confirmingActive;
  const status = terminalStatusCopy(terminal.phase, t);
  const completeBlocked = busy || terminal.operation === 'cancel';
  const cancelBlocked = busy || terminal.operation === 'complete';
  const locationBlocked = busy || requiresActiveConfirmation;

  return (
    <Glass radius={16} key={`active-session:${userId ?? 'anon'}:${session.id}`}>
      <View
        style={styles.column}
        accessibilityRole="summary"
        accessibilityLabel={t('parkingSession.active.a11y', { time: elapsed })}
        testID="active-parking-session"
      >
        <View style={styles.row}>
          <View style={[styles.iconBubble, { backgroundColor: colors.primaryFixed }]}>
            <MaterialCommunityIcons name="car-outline" size={18} color={colors.primary} />
          </View>
          <View style={styles.labels}>
            <AppText variant="bodySm" numberOfLines={1} style={styles.title}>
              {t('parkingSession.active.title')}
            </AppText>
            <View
              accessibilityLabel={t('parkingSession.active.elapsedA11y', { duration: elapsed })}
              testID="active-parking-elapsed"
            >
              <AppText variant="labelSm" color={colors.onSurfaceVariant} numberOfLines={1} tabular>
                {t('parkingSession.active.elapsed', { duration: elapsed })}
              </AppText>
            </View>
          </View>
          <Chip label={t('parkingSession.active.status')} selected size="sm" />
        </View>

        {status ? (
          <View style={styles.statusRow} testID="active-parking-terminal-status">
            <AppText
              variant="labelSm"
              color={colors.onSurfaceVariant}
              numberOfLines={2}
              style={styles.flexGrow}
            >
              {status.body}
            </AppText>
            {(terminal.phase === 'ambiguousComplete' ||
              terminal.phase === 'ambiguousCancel' ||
              terminal.phase === 'rejected') && (
              <Button
                label={t('parkingSession.terminal.retry')}
                size="sm"
                variant="tonal"
                disabled={busy}
                onPress={() => void terminal.retry()}
                accessibilityHint={
                  terminal.operation === 'cancel'
                    ? t('parkingSession.cancel.retryA11y')
                    : t('parkingSession.complete.retryA11y')
                }
              />
            )}
          </View>
        ) : null}

        {requiresActiveConfirmation ? (
          <View style={styles.staleBox} testID="active-parking-stale-confirm">
            <AppText variant="labelSm" numberOfLines={3}>
              {t('parkingSession.stale.prompt')}
            </AppText>
            <View style={styles.actions}>
              <Button
                label={
                  confirmingActive
                    ? t('parkingSession.stale.confirmBusy')
                    : t('parkingSession.stale.stillParked')
                }
                size="sm"
                loading={confirmingActive}
                disabled={busy}
                onPress={() => void confirmStillParked()}
                style={styles.flexGrow}
                testID="active-parking-still-parked"
              />
              <Button
                label={t('parkingSession.stale.alreadyLeft')}
                size="sm"
                variant="tonal"
                disabled={busy}
                onPress={() => setStaleLeaveConfirmOpen(true)}
                style={styles.flexGrow}
                testID="active-parking-already-left"
              />
            </View>
          </View>
        ) : (
          <>
            <View style={styles.utilityRow} testID="active-parking-location-actions">
              <IconButton
                icon="navigation-variant-outline"
                size={40}
                variant="surface"
                disabled={locationActions.navigateDisabled || locationBlocked}
                accessibilityLabel={t('parkingSession.navigate.a11y')}
                onPress={() => void locationActions.navigate()}
              />
              <IconButton
                icon="share-variant-outline"
                size={40}
                variant="surface"
                disabled={locationActions.shareDisabled || locationBlocked}
                accessibilityLabel={t('parkingSession.share.a11y')}
                onPress={() => void locationActions.share()}
              />
            </View>

            <View style={styles.actions}>
              <Button
                label={
                  terminal.phase === 'completing' || terminal.phase === 'reconciling'
                    ? t('parkingSession.complete.busy')
                    : t('parkingSession.complete.cta')
                }
                size="sm"
                loading={terminal.phase === 'completing' || terminal.phase === 'reconciling'}
                disabled={completeBlocked || locationActions.busy}
                onPress={() => setCompleteConfirmOpen(true)}
                accessibilityHint={t('parkingSession.complete.a11y')}
                style={styles.flexGrow}
                testID="active-parking-leave"
              />
              <Button
                label={
                  terminal.phase === 'cancelling'
                    ? t('parkingSession.cancel.busy')
                    : t('parkingSession.cancel.cta')
                }
                size="sm"
                variant="ghost"
                loading={terminal.phase === 'cancelling'}
                disabled={cancelBlocked || locationActions.busy}
                onPress={() => setCancelConfirmOpen(true)}
                accessibilityHint={t('parkingSession.cancel.a11y')}
                style={styles.flexGrow}
              />
            </View>
          </>
        )}
      </View>

      <ConfirmModal
        visible={completeConfirmOpen}
        title={t('parkingSession.complete.confirmTitle')}
        body={t('parkingSession.complete.confirmBody')}
        confirmLabel={t('parkingSession.complete.confirmCta')}
        cancelLabel={t('parkingSession.keepSession')}
        loading={terminal.phase === 'completing' || terminal.phase === 'reconciling'}
        onConfirm={() => {
          setCompleteConfirmOpen(false);
          void terminal.complete(session.id);
        }}
        onCancel={() => {
          if (!busy) setCompleteConfirmOpen(false);
        }}
      />

      <ConfirmModal
        visible={staleLeaveConfirmOpen}
        title={t('parkingSession.stale.leaveTitle')}
        body={t('parkingSession.stale.leaveBody')}
        confirmLabel={t('parkingSession.stale.leaveConfirmCta')}
        cancelLabel={t('parkingSession.stale.leaveCancel')}
        loading={terminal.phase === 'completing' || terminal.phase === 'reconciling'}
        onConfirm={() => {
          setStaleLeaveConfirmOpen(false);
          void terminal.complete(session.id);
        }}
        onCancel={() => {
          if (!busy) setStaleLeaveConfirmOpen(false);
        }}
      />

      <ConfirmModal
        visible={cancelConfirmOpen}
        title={t('parkingSession.cancel.confirmTitle')}
        body={t('parkingSession.cancel.confirmBody')}
        confirmLabel={t('parkingSession.cancel.confirmCta')}
        cancelLabel={t('common.cancel')}
        confirmVariant="destructive"
        loading={terminal.phase === 'cancelling'}
        onConfirm={() => {
          setCancelConfirmOpen(false);
          void terminal.cancel(session.id);
        }}
        onCancel={() => {
          if (!busy) setCancelConfirmOpen(false);
        }}
      />
    </Glass>
  );
}

function terminalStatusCopy(
  phase: TerminalParkingPhase,
  t: ReturnType<typeof useT>,
): { body: string } | null {
  switch (phase) {
    case 'reconciling':
      return { body: t('parkingSession.terminal.reconciling') };
    case 'ambiguousComplete':
      return { body: t('parkingSession.complete.ambiguousBody') };
    case 'ambiguousCancel':
      return { body: t('parkingSession.cancel.ambiguousBody') };
    case 'rejected':
      return { body: t('parkingSession.terminal.rejectedBody') };
    default:
      return null;
  }
}

const styles = StyleSheet.create({
  column: {
    gap: 8,
    paddingHorizontal: 12,
    paddingVertical: 9,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  actions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  utilityRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 8,
  },
  staleBox: {
    gap: 8,
  },
  flexGrow: { flexGrow: 1, flexBasis: 0 },
  iconBubble: {
    width: 34,
    height: 34,
    borderRadius: 17,
    alignItems: 'center',
    justifyContent: 'center',
  },
  labels: { flex: 1, gap: 1 },
  title: { fontFamily: 'Inter_600SemiBold' },
  leadSkeleton: { marginTop: 4, width: '70%' },
});
