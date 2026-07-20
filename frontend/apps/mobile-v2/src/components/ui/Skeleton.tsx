import { useEffect } from 'react';
import { type DimensionValue, type StyleProp, type ViewStyle } from 'react-native';
import Animated, {
  cancelAnimation,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withRepeat,
  withTiming,
} from 'react-native-reanimated';
import { useTheme } from '@/theme/ThemeProvider';

export interface SkeletonProps {
  width?: DimensionValue;
  height?: number;
  radius?: number;
  style?: StyleProp<ViewStyle>;
}

/** Shimmerless-when-reduced skeleton block on the tonal ramp. */
export function Skeleton({ width = '100%', height = 16, radius = 8, style }: SkeletonProps) {
  const theme = useTheme();
  const reduced = useReducedMotion();
  const pulse = useSharedValue(0.6);

  useEffect(() => {
    if (!reduced) {
      pulse.value = withRepeat(withTiming(1, { duration: 900 }), -1, true);
    }
    return () => cancelAnimation(pulse);
  }, [reduced, pulse]);

  const animatedStyle = useAnimatedStyle(() => ({ opacity: reduced ? 0.8 : pulse.value }));

  return (
    <Animated.View
      style={[
        {
          width,
          height,
          borderRadius: radius,
          backgroundColor: theme.colors.surfaceContainer3,
        },
        animatedStyle,
        style,
      ]}
    />
  );
}
