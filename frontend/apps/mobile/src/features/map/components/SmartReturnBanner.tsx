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
 * (`/map?smartReturn=1`). Signals that the view was auto-centered near home and a
 * nearby search was run automatically.
 */
function SmartReturnBannerImpl({ visible, topOffset, onDismiss }: SmartReturnBannerProps) {
  const theme = useTheme();
  if (!visible) return null;
  return (
    <View style={[styles.wrap, { top: topOffset }]} pointerEvents="box-none">
      <View
        accessibilityRole="summary"
        accessibilityLabel="Smart Return: showing parking near your home"
        style={[
          styles.banner,
          {
            backgroundColor: theme.colors.primarySoft,
            borderColor: theme.colors.primary,
            borderRadius: theme.radius.lg,
            ...theme.elevation.card,
          },
        ]}
      >
        <Ionicons name="home" size={18} color={theme.colors.primary} />
        <View style={styles.text}>
          <AppText variant="label" tone="primary">
            Smart Return
          </AppText>
          <AppText variant="caption" tone="muted">
            Showing available parking near your home.
          </AppText>
        </View>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Dismiss Smart Return banner"
          hitSlop={HIT_SLOP}
          onPress={onDismiss}
        >
          <Ionicons name="close" size={20} color={theme.colors.textMuted} />
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', left: 12, right: 12 },
  banner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    padding: 12,
    borderWidth: 1,
  },
  text: { flex: 1, gap: 2 },
});

export const SmartReturnBanner = memo(SmartReturnBannerImpl);
