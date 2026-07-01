import { StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { useTheme } from '@/theme';
import { AppText } from '@/components/ui/AppText';

/**
 * Floating offline pill, matching the web OfflineBanner: a centered rounded
 * surface chip below the status bar with a warning wifi icon, rather than a
 * full-width solid bar. Hidden until connectivity is known so it never flashes
 * on launch.
 */
export function OfflineBanner() {
  const online = useOnlineStatus();
  const theme = useTheme();
  const insets = useSafeAreaInsets();

  if (online !== false) return null;

  return (
    <View pointerEvents="none" style={[styles.wrap, { top: insets.top + 8 }]}>
      <View
        accessibilityRole="alert"
        style={[
          styles.pill,
          {
            backgroundColor: theme.colors.surface,
            borderColor: theme.colors.border,
            borderRadius: theme.radius.full,
            ...theme.elevation.floating,
          },
        ]}
      >
        <Ionicons name="cloud-offline-outline" size={16} color={theme.colors.warning} />
        <AppText variant="caption" style={{ color: theme.colors.text }}>
          You’re offline — showing the latest saved data.
        </AppText>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    position: 'absolute',
    left: 0,
    right: 0,
    zIndex: 2000,
    alignItems: 'center',
    paddingHorizontal: 8,
  },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    borderWidth: 1,
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
});
