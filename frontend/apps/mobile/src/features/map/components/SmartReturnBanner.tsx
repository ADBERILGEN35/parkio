import { Ionicons } from '@expo/vector-icons';
import { memo } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText } from '@/components/ui';
import { HIT_SLOP, useTheme } from '@/theme';

export interface SmartReturnBannerProps {
  visible: boolean;
  topOffset: number;
  onDismiss: () => void;
}

/**
 * Contextual banner shown when the map is opened from a Smart Return entry point
 * (`/map?smartReturn=1`). Mirrors the web mobile pill (rounded-full, primary-tinted,
 * one-line copy + dismiss); the web's translucent blur becomes the solid tonal
 * surface since native WebView overlays can't backdrop-blur the map.
 */
function SmartReturnBannerImpl({ visible, topOffset, onDismiss }: SmartReturnBannerProps) {
  const theme = useTheme();
  if (!visible) return null;
  return (
    <View style={[styles.wrap, { top: topOffset }]} pointerEvents="box-none">
      <View
        accessibilityRole="summary"
        accessibilityLabel="Smart Return: showing parking near your saved home"
        style={[
          styles.pill,
          {
            backgroundColor: theme.colors.surfaceMuted,
            borderRadius: theme.radius.full,
            ...theme.elevation.card,
          },
        ]}
      >
        <Ionicons name="home" size={14} color={theme.colors.primary} />
        <AppText variant="label" tone="primary" numberOfLines={1} style={styles.text}>
          Showing parking near your saved home.
        </AppText>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Dismiss Smart Return notice"
          hitSlop={HIT_SLOP}
          onPress={onDismiss}
        >
          <Ionicons name="close" size={16} color={theme.colors.primary} />
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', left: 12, right: 12, alignItems: 'center' },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 16,
    paddingVertical: 10,
    maxWidth: 430,
  },
  text: { flexShrink: 1 },
});

export const SmartReturnBanner = memo(SmartReturnBannerImpl);
