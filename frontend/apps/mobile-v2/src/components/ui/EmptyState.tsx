import { StyleSheet, View } from 'react-native';
import { AppText } from './AppText';
import { Button } from './Button';
import { PulseMotif } from './PulseMotif';
import { useTheme } from '@/theme/ThemeProvider';

export interface EmptyStateProps {
  title: string;
  body?: string;
  ctaLabel?: string;
  onCtaPress?: () => void;
}

/** Pulse-motif empty state: one-line invitation + primary action (brief §7.15). */
export function EmptyState({ title, body, ctaLabel, onCtaPress }: EmptyStateProps) {
  const theme = useTheme();
  return (
    <View style={styles.container}>
      <PulseMotif size={140} />
      <AppText variant="titleMd" align="center">
        {title}
      </AppText>
      {body ? (
        <AppText variant="bodyMd" align="center" color={theme.colors.onSurfaceVariant}>
          {body}
        </AppText>
      ) : null}
      {ctaLabel && onCtaPress ? (
        <Button label={ctaLabel} onPress={onCtaPress} block={false} size="md" style={styles.cta} />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { alignItems: 'center', gap: 10, paddingVertical: 32, paddingHorizontal: 24 },
  // Non-block buttons self-align flex-start; recenter inside the column.
  cta: { marginTop: 6, alignSelf: 'center' },
});
