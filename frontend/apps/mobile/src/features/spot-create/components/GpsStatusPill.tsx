import { memo } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui';
import { useLocale } from '@/i18n/LocaleProvider';
import { HIT_SLOP, MIN_TOUCH_TARGET, useTheme } from '@/theme';
import type { SpotLocationStatus } from '../hooks/useSpotCreationLocation';
import { formatAccuracy, gpsSignalLevel } from '../lib/locationAccuracy';

export interface GpsStatusPillProps {
  status: SpotLocationStatus;
  accuracyMeters: number | null;
  onRetry: () => void;
  onOpenSettings: () => void;
}

interface StatusVisual {
  tone: 'success' | 'warning' | 'danger';
  label: string;
  hint?: string;
  action?: { label: string; onPress: 'retry' | 'settings' };
  busy?: boolean;
}

function resolveVisual(
  status: SpotLocationStatus,
  accuracyMeters: number | null,
  t: (v: string) => string,
): StatusVisual {
  if (status === 'prompting' || status === 'locating') {
    return { tone: 'warning', label: t('Improving location…'), busy: true };
  }
  if (status === 'denied') {
    return {
      tone: 'danger',
      label: t('Location permission needed'),
      hint: t('Parkio uses GPS to place the spot where you stand.'),
      action: { label: t('Allow'), onPress: 'retry' },
    };
  }
  if (status === 'blocked') {
    return {
      tone: 'danger',
      label: t('Location permission denied'),
      hint: t('Enable location for Parkio in system settings.'),
      action: { label: t('Open Settings'), onPress: 'settings' },
    };
  }
  if (status === 'unavailable') {
    return {
      tone: 'danger',
      label: t('GPS unavailable'),
      hint: t('Move outside or near a window, then retry.'),
      action: { label: t('Retry GPS'), onPress: 'retry' },
    };
  }
  switch (gpsSignalLevel(accuracyMeters)) {
    case 'excellent':
      return { tone: 'success', label: t(`Excellent (${formatAccuracy(accuracyMeters)})`) };
    case 'good':
      return { tone: 'success', label: t(`Good (${formatAccuracy(accuracyMeters)})`) };
    case 'usable':
      return {
        tone: 'warning',
        label: t(`Usable (${formatAccuracy(accuracyMeters)})`),
        hint: t('A better fix helps drivers find the spot faster.'),
        action: { label: t('Refresh'), onPress: 'retry' },
      };
    case 'poor':
      return {
        tone: 'danger',
        label: t(`Too imprecise (${formatAccuracy(accuracyMeters)})`),
        hint: t('GPS accuracy is too low to publish.'),
        action: { label: t('Retry GPS'), onPress: 'retry' },
      };
    case 'none':
      return { tone: 'danger', label: t('Waiting for GPS'), action: { label: t('Retry'), onPress: 'retry' } };
  }
}

function GpsStatusPillImpl({ status, accuracyMeters, onRetry, onOpenSettings }: GpsStatusPillProps) {
  const theme = useTheme();
  const { t } = useLocale();
  const visual = resolveVisual(status, accuracyMeters, t);
  const dotColor =
    visual.tone === 'success' ? theme.colors.success : visual.tone === 'warning' ? theme.colors.warning : theme.colors.danger;

  return (
    <View style={styles.row} accessibilityLiveRegion="polite">
      {visual.busy ? (
        <ActivityIndicator size="small" color={dotColor} />
      ) : (
        <View style={[styles.dot, { backgroundColor: dotColor }]} />
      )}
      <View
        style={styles.textCol}
        accessible
        accessibilityLabel={`${t('GPS accuracy')}: ${visual.label}. ${visual.hint ?? ''}`}
      >
        <AppText variant="caption" tone="muted">
          {t('GPS accuracy')}
        </AppText>
        <AppText variant="subtitle">{visual.label}</AppText>
        {visual.hint ? (
          <AppText variant="caption" tone="muted">
            {visual.hint}
          </AppText>
        ) : null}
      </View>
      {visual.action ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={visual.action.label}
          hitSlop={HIT_SLOP}
          onPress={visual.action.onPress === 'settings' ? onOpenSettings : onRetry}
          style={({ pressed }) => [
            styles.action,
            {
              minHeight: MIN_TOUCH_TARGET,
              backgroundColor: pressed ? theme.colors.surfaceMuted : theme.colors.primarySoft,
              borderRadius: theme.radius.full,
            },
          ]}
        >
          <AppText variant="label" tone="primary">
            {visual.action.label}
          </AppText>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  dot: { width: 12, height: 12, borderRadius: 6 },
  textCol: { flex: 1, gap: 1 },
  action: { paddingHorizontal: 16, alignItems: 'center', justifyContent: 'center' },
});

export const GpsStatusPill = memo(GpsStatusPillImpl);
