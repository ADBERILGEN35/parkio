import type { StyleProp, ViewStyle } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { PressableScale } from './PressableScale';
import { shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface IconButtonProps {
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  onPress?: () => void;
  accessibilityLabel: string;
  size?: number;
  variant?: 'surface' | 'glassless' | 'primary' | 'destructiveGhost';
  disabled?: boolean;
  elevated?: boolean;
  style?: StyleProp<ViewStyle>;
}

/** Circular icon button (44pt touch target minimum). */
export function IconButton({
  icon,
  onPress,
  accessibilityLabel,
  size = 44,
  variant = 'surface',
  disabled,
  elevated,
  style,
}: IconButtonProps) {
  const theme = useTheme();
  const { colors } = theme;
  const palette = {
    surface: { bg: colors.surface, fg: colors.onSurface },
    glassless: { bg: 'transparent', fg: colors.onSurface },
    primary: { bg: colors.primary, fg: colors.onPrimary },
    destructiveGhost: { bg: colors.errorContainer, fg: colors.error },
  }[variant];

  return (
    <PressableScale
      scaleTo={0.9}
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ disabled: Boolean(disabled) }}
      style={[
        {
          width: size,
          height: size,
          borderRadius: size / 2,
          backgroundColor: palette.bg,
          alignItems: 'center',
          justifyContent: 'center',
          opacity: disabled ? 0.45 : 1,
        },
        elevated ? shadows.ambientSoft : null,
        style,
      ]}
    >
      <MaterialCommunityIcons name={icon} size={Math.round(size * 0.5)} color={palette.fg} />
    </PressableScale>
  );
}
