import { Ionicons } from '@expo/vector-icons';
import { memo, useEffect, useState } from 'react';
import { Animated, StyleSheet, View } from 'react-native';
import { useTheme } from '@/theme';

const PIN_SIZE = 44;
/** Vertical correction: the glyph's tip sits slightly above its box bottom. */
const TIP_CORRECTION = 4;
const LIFT_PX = 12;

/**
 * Fixed center pin rendered natively over the map (Uber/Airbnb picker pattern):
 * the map moves underneath, the pin tip always marks the selected coordinate.
 * While the camera moves (`lifted`) the pin rises and its ground dot shrinks;
 * on settle it springs back down — the "drop" that signals the spot is set.
 * Purely decorative for screen readers; the selected-area card carries the info.
 */
function CenterPinOverlayImpl({ lifted }: { lifted: boolean }) {
  const theme = useTheme();
  // Held in state (not a ref) so render-time interpolation satisfies react-hooks/refs.
  const [lift] = useState(() => new Animated.Value(0));

  useEffect(() => {
    if (lifted) {
      Animated.timing(lift, { toValue: 1, duration: 140, useNativeDriver: true }).start();
    } else {
      Animated.spring(lift, { toValue: 0, friction: 5, tension: 140, useNativeDriver: true }).start();
    }
  }, [lift, lifted]);

  const translateY = lift.interpolate({ inputRange: [0, 1], outputRange: [0, -LIFT_PX] });
  const dotScale = lift.interpolate({ inputRange: [0, 1], outputRange: [1, 0.55] });
  const dotOpacity = lift.interpolate({ inputRange: [0, 1], outputRange: [0.3, 0.15] });

  return (
    <View
      style={StyleSheet.absoluteFill}
      pointerEvents="none"
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <View style={styles.anchor}>
        <Animated.View
          style={[
            styles.dot,
            { backgroundColor: theme.colors.text, opacity: dotOpacity, transform: [{ scaleX: dotScale }, { scaleY: dotScale }] },
          ]}
        />
        <Animated.View style={[styles.pin, { transform: [{ translateY }] }]}>
          <Ionicons name="location-sharp" size={PIN_SIZE} color={theme.colors.primary} style={styles.pinGlyph} />
        </Animated.View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  // Zero-size anchor at the exact map center; children offset from it.
  anchor: {
    position: 'absolute',
    top: '50%',
    left: '50%',
    width: 0,
    height: 0,
  },
  dot: {
    position: 'absolute',
    left: -5,
    top: -2,
    width: 10,
    height: 4,
    borderRadius: 4,
  },
  pin: {
    position: 'absolute',
    left: -PIN_SIZE / 2,
    top: -PIN_SIZE + TIP_CORRECTION,
  },
  pinGlyph: {
    textShadowColor: 'rgba(11, 28, 48, 0.35)',
    textShadowOffset: { width: 0, height: 2 },
    textShadowRadius: 4,
  },
});

export const CenterPinOverlay = memo(CenterPinOverlayImpl);
