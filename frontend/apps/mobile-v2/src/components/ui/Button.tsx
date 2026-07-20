import type { ReactNode } from 'react';
import { ActivityIndicator, StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from './AppText';
import { PressableScale } from './PressableScale';
import { radius } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export type ButtonVariant = 'primary' | 'tonal' | 'ghost' | 'destructive';
export type ButtonSize = 'lg' | 'md' | 'sm';

export interface ButtonProps {
  label: string;
  onPress?: () => void;
  variant?: ButtonVariant;
  size?: ButtonSize;
  icon?: keyof typeof MaterialCommunityIcons.glyphMap;
  disabled?: boolean;
  loading?: boolean;
  /** Stretch to the container width (default true for lg). */
  block?: boolean;
  accessibilityHint?: string;
  style?: StyleProp<ViewStyle>;
  children?: ReactNode;
}

const HEIGHTS: Record<ButtonSize, number> = { lg: 52, md: 44, sm: 36 };

/** Pill buttons per the pen component sheet: primary / tonal / ghost / destructive. */
export function Button({
  label,
  onPress,
  variant = 'primary',
  size = 'lg',
  icon,
  disabled,
  loading,
  block,
  accessibilityHint,
  style,
}: ButtonProps) {
  const theme = useTheme();
  const { colors } = theme;

  const palette = {
    primary: { bg: colors.primary, fg: colors.onPrimary },
    tonal: { bg: colors.primaryFixed, fg: theme.mode === 'dark' ? colors.primaryFixedDim : colors.primary },
    ghost: { bg: 'transparent', fg: theme.mode === 'dark' ? colors.primaryFixedDim : colors.primary },
    destructive: { bg: colors.error, fg: theme.mode === 'dark' ? '#3A0E0C' : '#FFFFFF' },
  }[variant];

  const height = HEIGHTS[size];
  const isBlock = block ?? size === 'lg';
  const inactive = disabled || loading;

  return (
    <PressableScale
      onPress={onPress}
      disabled={inactive}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={accessibilityHint}
      accessibilityState={{ disabled: Boolean(inactive), busy: Boolean(loading) }}
      style={[
        styles.base,
        {
          height,
          borderRadius: radius.pill,
          backgroundColor: palette.bg,
          opacity: disabled ? 0.45 : 1,
          alignSelf: isBlock ? 'stretch' : 'flex-start',
          paddingHorizontal: size === 'sm' ? 14 : 22,
        },
        style,
      ]}
    >
      <View style={styles.content}>
        {loading ? (
          <ActivityIndicator size="small" color={palette.fg} />
        ) : (
          icon && (
            <MaterialCommunityIcons name={icon} size={size === 'sm' ? 16 : 19} color={palette.fg} />
          )
        )}
        <AppText
          variant={size === 'sm' ? 'bodySm' : 'titleMd'}
          color={palette.fg}
          numberOfLines={1}
          style={styles.label}
        >
          {label}
        </AppText>
      </View>
    </PressableScale>
  );
}

const styles = StyleSheet.create({
  base: { justifyContent: 'center' },
  content: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
  },
  label: { flexShrink: 1 },
});
