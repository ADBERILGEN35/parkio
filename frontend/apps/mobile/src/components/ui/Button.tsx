import { ActivityIndicator, Pressable, StyleSheet, View, type PressableProps } from 'react-native';
import { MIN_TOUCH_TARGET, useTheme } from '@/theme';
import { AppText } from './AppText';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';

export interface ButtonProps extends Omit<PressableProps, 'children' | 'style'> {
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

  const palette = {
    primary: { bg: theme.colors.primary, pressed: theme.colors.primaryPressed, fg: theme.colors.onPrimary, border: 'transparent' },
    secondary: { bg: theme.colors.surfaceMuted, pressed: theme.colors.border, fg: theme.colors.text, border: 'transparent' },
    ghost: { bg: 'transparent', pressed: theme.colors.surfaceMuted, fg: theme.colors.primary, border: 'transparent' },
    danger: { bg: theme.colors.danger, pressed: theme.colors.danger, fg: theme.colors.textInverse, border: 'transparent' },
  }[variant];

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: isDisabled, busy: loading }}
      disabled={isDisabled}
      style={({ pressed }) => [
        styles.base,
        {
          backgroundColor: pressed && !isDisabled ? palette.pressed : palette.bg,
          borderColor: palette.border,
          borderRadius: theme.radius.lg,
          opacity: isDisabled ? 0.55 : 1,
          alignSelf: fullWidth ? 'stretch' : 'flex-start',
          paddingHorizontal: theme.spacing.xl,
        },
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
    </Pressable>
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
