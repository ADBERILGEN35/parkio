import { memo } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui';
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

function resolveVisual(status: SpotLocationStatus, accuracyMeters: number | null): StatusVisual {
  if (status === 'prompting' || status === 'locating') {
    return { tone: 'warning', label: 'Improving location…', busy: true };
  }
  if (status === 'denied') {
    return {
      tone: 'danger',
      label: 'Location permission needed',
      hint: 'Parkio uses GPS to place the spot where you stand.',
      action: { label: 'Allow', onPress: 'retry' },
    };
  }
  if (status === 'blocked') {
    return {
      tone: 'danger',
      label: 'Location permission denied',
      hint: 'Enable location for Parkio in system settings.',
      action: { label: 'Open Settings', onPress: 'settings' },
    };
  }
  if (status === 'unavailable') {
    return {
      tone: 'danger',
      label: 'GPS unavailable',
      hint: 'Move outside or near a window, then retry.',
      action: { label: 'Retry GPS', onPress: 'retry' },
    };
  }
  switch (gpsSignalLevel(accuracyMeters)) {
    case 'excellent':
      return { tone: 'success', label: `Excellent (${formatAccuracy(accuracyMeters)})` };
    case 'good':
      return { tone: 'success', label: `Good (${formatAccuracy(accuracyMeters)})` };
    case 'usable':
      return {
        tone: 'warning',
        label: `Usable (${formatAccuracy(accuracyMeters)})`,
        hint: 'A better fix helps drivers find the spot faster.',
        action: { label: 'Refresh', onPress: 'retry' },
      };
    case 'poor':
      return {
        tone: 'danger',
        label: `Too imprecise (${formatAccuracy(accuracyMeters)})`,
        hint: 'GPS accuracy is too low to publish.',
        action: { label: 'Retry GPS', onPress: 'retry' },
      };
    case 'none':
      return { tone: 'danger', label: 'Waiting for GPS', action: { label: 'Retry', onPress: 'retry' } };
  }
}

/**
 * Traffic-light GPS status row — replaces the raw "GPS location · 5 m" text.
 * Same thresholds as the submit gate ({@link gpsSignalLevel}), so what the user
 * sees always matches what the Share button allows.
 */
function GpsStatusPillImpl({ status, accuracyMeters, onRetry, onOpenSettings }: GpsStatusPillProps) {
  const theme = useTheme();
  const visual = resolveVisual(status, accuracyMeters);
  const dotColor =
    visual.tone === 'success' ? theme.colors.success : visual.tone === 'warning' ? theme.colors.warning : theme.colors.danger;

  return (
    <View style={styles.row} accessibilityLiveRegion="polite">
      {visual.busy ? (
        <ActivityIndicator size="small" color={dotColor} />
      ) : (
        <View style={[styles.dot, { backgroundColor: dotColor }]} />
      )}
      <View style={styles.textCol} accessible accessibilityLabel={`GPS accuracy: ${visual.label}. ${visual.hint ?? ''}`}>
        <AppText variant="caption" tone="muted">
          GPS accuracy
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
