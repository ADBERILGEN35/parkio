import type { ReactNode } from 'react';
import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import { BlurView } from 'expo-blur';
import { useTheme } from '@/theme/ThemeProvider';

export interface GlassProps {
  children?: ReactNode;
  /** Corner radius — full pill for the search bar, 16–24 for cards/sheets. */
  radius?: number;
  intensity?: number;
  style?: StyleProp<ViewStyle>;
  contentStyle?: StyleProp<ViewStyle>;
}

/**
 * The glass recipe for anything floating over the map (brief §4.4): tinted
 * translucent fill + backdrop blur + 1px hairline. BlurView renders real blur
 * on iOS; on Android the tint fill alone carries the effect (blur there is
 * costly/inconsistent — the higher-alpha fill keeps text readable).
 */
export function Glass({ children, radius = 24, intensity = 20, style, contentStyle }: GlassProps) {
  const theme = useTheme();
  return (
    <View
      style={[
        {
          borderRadius: radius,
          overflow: 'hidden',
          borderWidth: StyleSheet.hairlineWidth,
          borderColor: theme.colors.glassHairline,
          backgroundColor: theme.colors.glass,
        },
        style,
      ]}
    >
      <BlurView
        intensity={intensity}
        tint={theme.mode === 'dark' ? 'dark' : 'light'}
        style={StyleSheet.absoluteFill}
      />
      <View style={contentStyle}>{children}</View>
    </View>
  );
}
