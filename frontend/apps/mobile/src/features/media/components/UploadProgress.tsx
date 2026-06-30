import { StyleSheet, View } from 'react-native';
import { AppText, Button } from '@/components/ui';
import { useTheme } from '@/theme';
import type { UploadPhase } from '../types';

export interface UploadProgressProps {
  phase: UploadPhase;
  /** 0–1 fraction of bytes uploaded. */
  progress: number;
  /** Human-readable error, when phase is 'error'. */
  errorMessage?: string;
  /** True while connectivity is lost mid-upload. */
  offline?: boolean;
  onCancel: () => void;
  onRetry: () => void;
}

/**
 * Upload status surface: an accessible determinate progress bar while uploading,
 * plus Cancel, and Retry on failure. The bar exposes `progressbar` semantics with
 * a live percentage so screen readers announce upload progress.
 */
export function UploadProgress({
  phase,
  progress,
  errorMessage,
  offline,
  onCancel,
  onRetry,
}: UploadProgressProps) {
  const theme = useTheme();
  const pct = Math.round(Math.min(Math.max(progress, 0), 1) * 100);
  const active = phase === 'preparing' || phase === 'uploading';

  const statusLine =
    phase === 'preparing'
      ? 'Preparing photo…'
      : offline
        ? 'Waiting for connection…'
        : phase === 'uploading'
          ? `Uploading… ${pct}%`
          : phase === 'error'
            ? (errorMessage ?? 'Upload failed.')
            : '';

  return (
    <View style={styles.container} testID="upload-progress">
      <View
        accessible
        accessibilityRole="progressbar"
        accessibilityValue={{ min: 0, max: 100, now: pct }}
        accessibilityLabel="Upload progress"
        style={[styles.track, { backgroundColor: theme.colors.surfaceMuted, borderRadius: theme.radius.full }]}
      >
        <View
          style={[
            styles.fill,
            {
              width: `${pct}%`,
              backgroundColor: phase === 'error' ? theme.colors.danger : theme.colors.primary,
              borderRadius: theme.radius.full,
            },
          ]}
        />
      </View>

      <AppText variant="callout" tone={phase === 'error' ? 'danger' : 'muted'} style={styles.status}>
        {statusLine}
      </AppText>

      <View style={styles.actions}>
        {phase === 'error' ? (
          <Button label="Retry upload" onPress={onRetry} disabled={offline} />
        ) : null}
        {active ? <Button label="Cancel" variant="secondary" onPress={onCancel} /> : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: 12 },
  track: { height: 8, width: '100%', overflow: 'hidden' },
  fill: { height: '100%' },
  status: { textAlign: 'center' },
  actions: { gap: 8 },
});
