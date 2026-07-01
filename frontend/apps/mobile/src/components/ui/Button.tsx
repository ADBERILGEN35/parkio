import { ActivityIndicator, StyleSheet, TouchableOpacity, View, type TouchableOpacityProps } from 'react-native';
import { MIN_TOUCH_TARGET, useTheme } from '@/theme';
import { AppText } from './AppText';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';

export interface ButtonProps extends Omit<TouchableOpacityProps, 'children' | 'style'> {
  label: string;
  variant?: Variant;
  loading?: boolean;
  fullWidth?: boolean;
  /** Optional leading element (e.g. an icon). */
  leading?: React.ReactNode;
}

/**
 * Primary action component. Enforces the 44pt minimum touch target, a clear
 * pressed state, disabled styling, and an inline loading spinner that also blocks
 * repeat taps. Exposes proper accessibility role/state.
 */
export function Button({
  label,
  variant = 'primary',
  loading = false,
  fullWidth = true,
  leading,
  disabled,
  ...props
}: ButtonProps) {
  const theme = useTheme();
  const isDisabled = disabled || loading;

  // Web design-system button recipes: full pills, solid primary/danger carry a
  // soft shadow, secondary is the tonal surface-container pill, ghost is
  // text-only in on-surface-variant.
  const palette = {
    primary: { bg: theme.colors.primary, fg: theme.colors.onPrimary, border: 'transparent', shadow: true },
    secondary: { bg: theme.colors.surfaceMuted, fg: theme.colors.text, border: 'transparent', shadow: false },
    ghost: { bg: 'transparent', fg: theme.colors.textMuted, border: 'transparent', shadow: false },
    danger: { bg: theme.colors.danger, fg: theme.colors.onPrimary, border: 'transparent', shadow: true },
  }[variant];

  return (
    <TouchableOpacity
      accessibilityRole="button"
      accessibilityState={{ disabled: isDisabled, busy: loading }}
      disabled={isDisabled}
      activeOpacity={0.82}
      style={[
        styles.base,
        {
          backgroundColor: palette.bg,
          borderColor: palette.border,
          borderRadius: theme.radius.full,
          opacity: isDisabled ? 0.6 : 1,
          alignSelf: fullWidth ? 'stretch' : 'flex-start',
          paddingHorizontal: theme.spacing.lg,
        },
        palette.shadow && !isDisabled ? theme.elevation.card : null,
      ]}
      {...props}
    >
      {loading ? (
        <ActivityIndicator color={palette.fg} />
      ) : (
        <View style={styles.content}>
          {leading}
          <AppText variant="label" style={{ color: palette.fg }}>
            {label}
          </AppText>
        </View>
      )}
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  base: {
    minHeight: MIN_TOUCH_TARGET,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  content: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
});
