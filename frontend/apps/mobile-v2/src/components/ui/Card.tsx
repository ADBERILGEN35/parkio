import type { ReactNode } from 'react';
import { View, type StyleProp, type ViewStyle } from 'react-native';
import { radius as radiusTokens, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface CardProps {
  children?: ReactNode;
  /** Tonal step instead of a border (brief §5.4): 0 = white surface, 1–4 = ramp. */
  tone?: 0 | 1 | 2 | 3 | 4;
  radius?: number;
  padding?: number;
  shadow?: boolean;
  style?: StyleProp<ViewStyle>;
}

/** Surface container — hierarchy via the tonal ramp, never bordered boxes. */
export function Card({
  children,
  tone = 0,
  radius = radiusTokens.card,
  padding = 16,
  shadow = tone === 0,
  style,
}: CardProps) {
  const theme = useTheme();
  const { colors } = theme;
  const backgrounds = [
    colors.surface,
    colors.surfaceContainer1,
    colors.surfaceContainer2,
    colors.surfaceContainer3,
    colors.surfaceContainer4,
  ] as const;

  return (
    <View
      style={[
        {
          backgroundColor: backgrounds[tone],
          borderRadius: radius,
          padding,
        },
        shadow && theme.mode === 'light' ? shadows.ambientSoft : null,
        style,
      ]}
    >
      {children}
    </View>
  );
}
