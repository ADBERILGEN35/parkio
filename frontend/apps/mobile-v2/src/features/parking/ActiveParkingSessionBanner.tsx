import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { ConfirmModal } from '@/components/ui/ConfirmModal';
import { Glass } from '@/components/ui/Glass';
import { IconButton } from '@/components/ui/IconButton';
import { Skeleton } from '@/components/ui/Skeleton';
import { useAuthStore } from '@/state/authStore';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';
import { formatElapsedFromStartedAt } from './elapsedDuration';
import { useActiveParkingSession } from './useActiveParkingSession';
import { useNowTicker } from './useNowTicker';
import { useParkingLocationActions } from './useParkingLocationActions';
import {
  useTerminalParkingSession,
  type TerminalParkingPhase,
} from './useTerminalParkingSession';

/**
 * Map chrome strip for the ACTIVE ParkingSession (S1-P0-02 / S1-P0-04 / S1-P0-10).
 * Elapsed + terminal actions, plus compact return-navigation and location share.
 */
export function ActiveParkingSessionBanner() {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const userId = useAuthStore((s) => s.user?.id ?? null);
  const query = useActiveParkingSession();
  const sessionId = query.data?.id ?? null;
  const terminal = useTerminalParkingSession(sessionId);
  const locationActions = useParkingLocationActions({
    sessionId,
    latitude: query.data?.latitude,
    longitude: query.data?.longitude,
    terminalBusy: terminal.busy,
  });
  const [cancelConfirmOpen, setCancelConfirmOpen] = useState(false);

  const tickerEnabled = Boolean(query.data) && query.data?.status === 'ACTIVE';
  const now = useNowTicker(tickerEnabled);

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

  // HTTP 204 / null — normal empty. Do not occupy map chrome.
  if (!query.data) {
    return null;
  }

  const session = query.data;
  const elapsed = formatElapsedFromStartedAt(session.startedAt, now);
  const busy = terminal.busy;
  const status = terminalStatusCopy(terminal.phase, t);
  // Keep complete/cancel mutually exclusive across ambiguous retry windows.
  const completeBlocked = busy || terminal.operation === 'cancel';
  const cancelBlocked = busy || terminal.operation === 'complete';

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
            <AppText variant="labelSm" color={colors.onSurfaceVariant} numberOfLines={2} style={styles.flexGrow}>
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

        <View style={styles.utilityRow} testID="active-parking-location-actions">
          <IconButton
            icon="navigation-variant-outline"
            size={40}
            variant="surface"
            disabled={locationActions.navigateDisabled}
            accessibilityLabel={t('parkingSession.navigate.a11y')}
            onPress={() => void locationActions.navigate()}
          />
          <IconButton
            icon="share-variant-outline"
            size={40}
            variant="surface"
            disabled={locationActions.shareDisabled}
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
            onPress={() => void terminal.complete(session.id)}
            accessibilityHint={t('parkingSession.complete.a11y')}
            style={styles.flexGrow}
          />
          <Button
            label={
              terminal.phase === 'cancelling' ? t('parkingSession.cancel.busy') : t('parkingSession.cancel.cta')
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
      </View>

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
          if (!busy) {
            setCancelConfirmOpen(false);
          }
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