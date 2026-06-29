import { StyleSheet, View } from 'react-native';
import { useTheme } from '@/theme';
import { AppText } from './AppText';

export type BadgeTone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger';

export interface BadgeProps {
  label: string;
  tone?: BadgeTone;
}

/** Small soft status pill. */
export function Badge({ label, tone = 'neutral' }: BadgeProps) {
  const theme = useTheme();
  const map = {
    neutral: { bg: theme.colors.surfaceMuted, fg: theme.colors.textMuted },
    primary: { bg: theme.colors.primarySoft, fg: theme.colors.primary },
    success: { bg: theme.colors.successSoft, fg: theme.colors.success },
    warning: { bg: theme.colors.warningSoft, fg: theme.colors.warning },
    danger: { bg: theme.colors.dangerSoft, fg: theme.colors.danger },
  }[tone];

  return (
    <View style={[styles.badge, { backgroundColor: map.bg, borderRadius: theme.radius.full }]}>
      <AppText variant="caption" style={{ color: map.fg, fontWeight: '600' }}>
        {label}
      </AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: { alignSelf: 'flex-start', paddingHorizontal: 10, paddingVertical: 4 },
});
