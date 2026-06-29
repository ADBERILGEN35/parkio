import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { useTheme } from '@/theme';
import { AppText } from '@/components/ui/AppText';

/**
 * Thin top banner shown only while offline. Hidden until connectivity is known so
 * it never flashes on launch.
 */
export function OfflineBanner() {
  const online = useOnlineStatus();
  const theme = useTheme();
  const insets = useSafeAreaInsets();

  if (online !== false) return null;

  return (
    <View
      accessibilityRole="alert"
      style={[styles.banner, { backgroundColor: theme.colors.warning, paddingTop: insets.top + 6 }]}
    >
      <AppText variant="caption" style={{ color: '#fff', fontWeight: '600' }}>
        You’re offline — showing the latest saved data.
      </AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: { paddingBottom: 6, alignItems: 'center', justifyContent: 'center' },
});
