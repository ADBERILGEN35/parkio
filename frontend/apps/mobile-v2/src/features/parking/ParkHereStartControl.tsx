import { Linking, StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Glass } from '@/components/ui/Glass';
import { Skeleton } from '@/components/ui/Skeleton';
import type { LocationState } from '@/features/map/hooks';
import { useT, type Translator } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';
import { useActiveParkingSession } from './useActiveParkingSession';
import { useStartParkingSession, type StartParkingPhase } from './useStartParkingSession';

/**
 * Map chrome strip for manual “Park ettim” (S1-P0-03).
 * Hidden while an ACTIVE session is known; reuses Glass/Button density from Smart Return.
 */
export function ParkHereStartControl({ location }: { location: LocationState }) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const active = useActiveParkingSession();
  const startControls = useStartParkingSession(location);

  // Do not compete with a restored/created ACTIVE session.
  if (active.data) {
    return null;
  }
  // Wait for active restore before offering start (avoids flash + false dual CTA).
  if (active.isPending) {
    return null;
  }

  const { phase, busy, start, retry } = startControls;

  if (phase === 'idle') {
    return (
      <Glass radius={16}>
        <View style={styles.row} testID="park-here-start">
          <View style={[styles.iconBubble, { backgroundColor: colors.primaryFixed }]}>
            <MaterialCommunityIcons name="car-outline" size={18} color={colors.primary} />
          </View>
          <View style={styles.labels}>
            <AppText variant="bodySm" numberOfLines={1} style={styles.title}>
              {t('parkingSession.start.title')}
            </AppText>
            <AppText variant="labelSm" color={colors.onSurfaceVariant} numberOfLines={1}>
              {t('parkingSession.start.lead')}
            </AppText>
          </View>
          <Button
            label={t('parkingSession.start.cta')}
            size="sm"
            loading={busy}
            disabled={busy}
            onPress={() => void start()}
            accessibilityHint={t('parkingSession.start.a11y')}
          />
        </View>
      </Glass>
    );
  }

  if (isBusyPhase(phase)) {
    return (
      <Glass radius={16}>
        <View
          style={styles.row}
          accessibilityRole="progressbar"
          accessibilityLabel={busyLabel(phase, t)}
          testID="park-here-busy"
        >
          <Skeleton width={34} height={34} radius={17} />
          <View style={styles.labels}>
            <AppText variant="bodySm" numberOfLines={1} style={styles.title}>
              {busyLabel(phase, t)}
            </AppText>
            <Skeleton height={12} radius={4} style={styles.leadSkeleton} />
          </View>
        </View>
      </Glass>
    );
  }

  const failure = failureCopy(phase, t);
  if (!failure) {
    return null;
  }

  return (
    <Glass radius={16}>
      <View
        style={styles.row}
        accessibilityRole="summary"
        accessibilityLabel={failure.title}
        testID="park-here-error"
      >
        <View style={[styles.iconBubble, { backgroundColor: colors.surfaceContainer2 }]}>
          <MaterialCommunityIcons name={failure.icon} size={18} color={colors.tertiary} />
        </View>
        <View style={styles.labels}>
          <AppText variant="bodySm" numberOfLines={1} style={styles.title}>
            {failure.title}
          </AppText>
          <AppText variant="labelSm" color={colors.onSurfaceVariant} numberOfLines={2}>
            {failure.body}
          </AppText>
        </View>
        {phase === 'permissionDeniedSettings' ? (
          <Button
            label={t('map.permission.openSettings')}
            size="sm"
            variant="tonal"
            onPress={() => void Linking.openSettings()}
          />
        ) : phase === 'permissionDeniedAsk' ? (
          <Button
            label={t('common.allow')}
            size="sm"
            variant="tonal"
            loading={busy}
            onPress={() => void retry()}
          />
        ) : (
          <Button
            label={t('common.retry')}
            size="sm"
            variant="tonal"
            loading={busy}
            onPress={() => void retry()}
          />
        )}
      </View>
    </Glass>
  );
}

function isBusyPhase(phase: StartParkingPhase): boolean {
  return (
    phase === 'requestingPermission' ||
    phase === 'acquiringLocation' ||
    phase === 'submitting' ||
    phase === 'reconciling'
  );
}

function busyLabel(phase: StartParkingPhase, t: Translator): string {
  switch (phase) {
    case 'requestingPermission':
      return t('parkingSession.start.requestingPermission');
    case 'acquiringLocation':
      return t('parkingSession.start.acquiringLocation');
    case 'submitting':
      return t('parkingSession.start.submitting');
    case 'reconciling':
      return t('parkingSession.start.reconciling');
    default:
      return t('parkingSession.start.submitting');
  }
}

function failureCopy(
  phase: StartParkingPhase,
  t: Translator,
): { title: string; body: string; icon: keyof typeof MaterialCommunityIcons.glyphMap } | null {
  switch (phase) {
    case 'permissionDeniedAsk':
      return {
        title: t('parkingSession.start.permissionTitle'),
        body: t('parkingSession.start.permissionAskBody'),
        icon: 'map-marker-off-outline',
      };
    case 'permissionDeniedSettings':
      return {
        title: t('parkingSession.start.permissionTitle'),
        body: t('parkingSession.start.permissionSettingsBody'),
        icon: 'map-marker-off-outline',
      };
    case 'locationFailed':
      return {
        title: t('parkingSession.start.locationFailedTitle'),
        body: t('parkingSession.start.locationFailedBody'),
        icon: 'crosshairs-gps',
      };
    case 'offline':
      return {
        title: t('parkingSession.start.offlineTitle'),
        body: t('parkingSession.start.offlineBody'),
        icon: 'wifi-off',
      };
    case 'ambiguous':
      return {
        title: t('parkingSession.start.ambiguousTitle'),
        body: t('parkingSession.start.ambiguousBody'),
        icon: 'cloud-off-outline',
      };
    case 'reconcileFailed':
      return {
        title: t('parkingSession.start.reconcileFailedTitle'),
        body: t('parkingSession.start.reconcileFailedBody'),
        icon: 'car-off',
      };
    case 'rejected':
      return {
        title: t('parkingSession.start.rejectedTitle'),
        body: t('parkingSession.start.rejectedBody'),
        icon: 'alert-circle-outline',
      };
    default:
      return null;
  }
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 12,
    paddingVertical: 9,
  },
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