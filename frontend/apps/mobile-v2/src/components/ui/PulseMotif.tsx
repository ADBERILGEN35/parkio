import { useEffect } from 'react';
import { View, type StyleProp, type ViewStyle } from 'react-native';
import Animated, {
  Easing,
  cancelAnimation,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withDelay,
  withRepeat,
  withTiming,
} from 'react-native-reanimated';
import { useTheme } from '@/theme/ThemeProvider';

export interface PulseMotifProps {
  /** Diameter of the outermost ring. */
  size?: number;
  /** Ring count (3–4 reads best). */
  rings?: number;
  animated?: boolean;
  /** Center dot color; rings derive from primary at stepped opacity. */
  color?: string;
  /** Hide the center dot when the motif is a backdrop behind other content. */
  core?: boolean;
  style?: StyleProp<ViewStyle>;
}

function Ring({
  diameter,
  color,
  opacity,
  animated,
  delay,
}: {
  diameter: number;
  color: string;
  opacity: number;
  animated: boolean;
  delay: number;
}) {
  const progress = useSharedValue(0);
  const reduced = useReducedMotion();
  const shouldAnimate = animated && !reduced;

  useEffect(() => {
    if (shouldAnimate) {
      progress.value = withDelay(
        delay,
        withRepeat(withTiming(1, { duration: 2600, easing: Easing.out(Easing.quad) }), -1, false),
      );
    }
    return () => cancelAnimation(progress);
  }, [shouldAnimate, delay, progress]);

  const style = useAnimatedStyle(() => {
    if (!shouldAnimate) {
      return { opacity };
    }
    return {
      opacity: opacity * (1 - progress.value * 0.8),
      transform: [{ scale: 1 + progress.value * 0.12 }],
    };
  });

  return (
    <Animated.View
      style={[
        {
          position: 'absolute',
          width: diameter,
          height: diameter,
          borderRadius: diameter / 2,
          borderWidth: 1.5,
          borderColor: color,
        },
        style,
      ]}
    />
  );
}

/**
 * The pulse motif (brief §5.2): concentric radar rings — Parkio's only
 * illustration system. Used in empty states, onboarding, permission cards and
 * celebrations. Static under reduced motion.
 */
export function PulseMotif({
  size = 160,
  rings = 3,
  animated = true,
  color,
  core = true,
  style,
}: PulseMotifProps) {
  const theme = useTheme();
  const ringColor = color ?? theme.colors.primary;

  return (
    <View
      style={[{ width: size, height: size, alignItems: 'center', justifyContent: 'center' }, style]}
      accessibilityElementsHidden
    >
      {Array.from({ length: rings }, (_, index) => {
        const diameter = size * ((index + 1) / rings);
        return (
          <Ring
            key={index}
            diameter={diameter}
            color={ringColor}
            opacity={0.16 - index * 0.04}
            animated={animated}
            delay={index * 350}
          />
        );
      })}
      {core && (
        <View
          style={{
            width: 10,
            height: 10,
            borderRadius: 5,
            backgroundColor: ringColor,
          }}
        />
      )}
    </View>
  );
}
