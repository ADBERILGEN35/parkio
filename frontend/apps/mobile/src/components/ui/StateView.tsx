import type { ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';
import { useTheme } from '@/theme';
import { AppText } from './AppText';
import { Button } from './Button';

export interface StateViewProps {
  title: string;
  description?: string;
  /** Emoji or icon glyph shown in the disc. Kept simple (no icon font dep yet). */
  glyph?: string;
  actionLabel?: string;
  onAction?: () => void;
  children?: ReactNode;
}

/**
 * Shared centered state: used for empty states, error+retry, and "nothing here
 * yet" screens. Keeps copy human and offers a single clear action.
 */
export function StateView({ title, description, glyph = '✦', actionLabel, onAction, children }: StateViewProps) {
  const theme = useTheme();
  return (
    <View style={styles.container} accessible accessibilityRole="summary">
      <View
        style={[
          styles.disc,
          { backgroundColor: theme.colors.skeleton, borderRadius: theme.radius.full },
        ]}
      >
        <AppText variant="display" tone="primary">
          {glyph}
        </AppText>
      </View>
      <AppText variant="title" style={styles.title}>
        {title}
      </AppText>
      {description ? (
        <AppText variant="body" tone="muted" style={styles.description}>
          {description}
        </AppText>
      ) : null}
      {actionLabel && onAction ? (
        <View style={styles.action}>
          <Button label={actionLabel} onPress={onAction} fullWidth={false} />
        </View>
      ) : null}
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, gap: 8 },
  disc: { width: 96, height: 96, alignItems: 'center', justifyContent: 'center', marginBottom: 16 },
  title: { textAlign: 'center' },
  description: { textAlign: 'center', maxWidth: 320 },
  action: { marginTop: 16 },
});
