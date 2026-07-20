import { forwardRef, useCallback } from 'react';
import { Pressable, type PressableProps, type View } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

export interface PressableScaleProps extends PressableProps {
  /** Pressed scale (brief §4.4: press = 0.95). */
  scaleTo?: number;
}

/**
 * Shared press feedback: 100ms scale-down on press, spring-ish release.
 * Respects reduced motion (no transform, opacity fallback).
 */
export const PressableScale = forwardRef<View, PressableScaleProps>(function PressableScale(
  { scaleTo = 0.95, onPressIn, onPressOut, style, disabled, ...rest },
  ref,
) {
  const pressed = useSharedValue(0);
  const reducedMotion = useReducedMotion();

  const animatedStyle = useAnimatedStyle(() => {
    if (reducedMotion) {
      return { opacity: pressed.value ? 0.85 : 1 };
    }
    return {
      transform: [{ scale: 1 - pressed.value * (1 - scaleTo) }],
    };
  });

  const handlePressIn = useCallback<NonNullable<PressableProps['onPressIn']>>(
    (event) => {
      pressed.value = withTiming(1, { duration: 100 });
      onPressIn?.(event);
    },
    [onPressIn, pressed],
  );

  const handlePressOut = useCallback<NonNullable<PressableProps['onPressOut']>>(
    (event) => {
      pressed.value = withTiming(0, { duration: 180 });
      onPressOut?.(event);
    },
    [onPressOut, pressed],
  );

  return (
    <AnimatedPressable
      ref={ref}
      {...rest}
      disabled={disabled}
      onPressIn={handlePressIn}
      onPressOut={handlePressOut}
      style={[animatedStyle, typeof style === 'function' ? undefined : style]}
    />
  );
});
