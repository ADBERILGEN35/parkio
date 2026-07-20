import { useEffect } from 'react';
import { Pressable } from 'react-native';
import Animated, {
  interpolateColor,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import { useTheme } from '@/theme/ThemeProvider';

export interface ToggleProps {
  value: boolean;
  onValueChange: (value: boolean) => void;
  disabled?: boolean;
  accessibilityLabel?: string;
}

const WIDTH = 48;
const HEIGHT = 28;
const KNOB = 22;

/** Animated switch matching the pen kit (off = outline-variant track). */
export function Toggle({ value, onValueChange, disabled, accessibilityLabel }: ToggleProps) {
  const theme = useTheme();
  const progress = useSharedValue(value ? 1 : 0);

  useEffect(() => {
    progress.value = withTiming(value ? 1 : 0, { duration: 180 });
  }, [value, progress]);

  const trackStyle = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(
      progress.value,
      [0, 1],
      [theme.colors.outlineVariant, theme.colors.primary],
    ),
  }));

  const knobStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: progress.value * (WIDTH - KNOB - 6) }],
  }));

  return (
    <Pressable
      onPress={() => onValueChange(!value)}
      disabled={disabled}
      accessibilityRole="switch"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ checked: value, disabled: Boolean(disabled) }}
      hitSlop={8}
      style={{ opacity: disabled ? 0.45 : 1 }}
    >
      <Animated.View
        style={[
          {
            width: WIDTH,
            height: HEIGHT,
            borderRadius: HEIGHT / 2,
            justifyContent: 'center',
            paddingHorizontal: 3,
          },
          trackStyle,
        ]}
      >
        <Animated.View
          style={[
            {
              width: KNOB,
              height: KNOB,
              borderRadius: KNOB / 2,
              backgroundColor: '#FFFFFF',
            },
            knobStyle,
          ]}
        />
      </Animated.View>
    </Pressable>
  );
}
