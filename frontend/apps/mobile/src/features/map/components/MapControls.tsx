import { Ionicons } from '@expo/vector-icons';
import { memo } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';
import { HIT_SLOP, MIN_TOUCH_TARGET, useTheme } from '@/theme';

export interface MapControlsProps {
  /** Bottom offset so controls sit above the sheet / safe area. */
  bottomOffset: number;
  onRecenter: () => void;
  /** Highlights the recenter FAB when the camera is following the user. */
  following: boolean;
  /** Spinner on the recenter FAB while a fix is in flight. */
  locating: boolean;
}

/**
 * Floating map controls (recenter). Each control meets the 44pt touch target and
 * exposes an accessibility label. Zoom buttons are intentionally omitted —
 * pinch-zoom is the primary gesture and on-screen zoom adds clutter; they can be
 * added here later without touching the screen.
 */
function MapControlsImpl({ bottomOffset, onRecenter, following, locating }: MapControlsProps) {
  const theme = useTheme();
  return (
    <View style={[styles.container, { bottom: bottomOffset }]} pointerEvents="box-none">
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Center map on my location"
        accessibilityState={{ selected: following, busy: locating }}
        hitSlop={HIT_SLOP}
        onPress={onRecenter}
        style={({ pressed }) => [
          styles.fab,
          {
            backgroundColor: pressed ? theme.colors.surfaceMuted : theme.colors.surface,
            borderRadius: theme.radius.full,
            ...theme.elevation.floating,
          },
        ]}
      >
        {locating ? (
          <ActivityIndicator color={theme.colors.primary} />
        ) : (
          <Ionicons
            name={following ? 'navigate' : 'navigate-outline'}
            size={22}
            color={following ? theme.colors.primary : theme.colors.text}
          />
        )}
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { position: 'absolute', right: 16, alignItems: 'flex-end', gap: 12 },
  fab: {
    width: MIN_TOUCH_TARGET,
    height: MIN_TOUCH_TARGET,
    alignItems: 'center',
    justifyContent: 'center',
  },
});

export const MapControls = memo(MapControlsImpl);
