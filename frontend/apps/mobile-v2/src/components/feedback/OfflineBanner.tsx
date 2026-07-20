import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Animated, { FadeInDown, FadeOutDown } from 'react-native-reanimated';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { useT } from '@/i18n/LocaleProvider';
import { radius, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

/** Floating pill above the tab bar while the device is offline. */
export function OfflineBanner() {
  const online = useOnlineStatus();
  const theme = useTheme();
  const t = useT();
  const insets = useSafeAreaInsets();

  if (online) {
    return null;
  }

  return (
    <View pointerEvents="none" style={[styles.host, { bottom: insets.bottom + 92 }]}>
      <Animated.View
        entering={FadeInDown.duration(250)}
        exiting={FadeOutDown.duration(200)}
        style={[styles.pill, { backgroundColor: theme.colors.inverseSurface }, shadows.ambientDeep]}
      >
        <MaterialCommunityIcons name="wifi-off" size={16} color={theme.colors.onInverseSurface} />
        <AppText variant="bodySm" color={theme.colors.onInverseSurface}>
          {t('common.offline')}
        </AppText>
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  host: { position: 'absolute', left: 0, right: 0, alignItems: 'center', zIndex: 90 },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: radius.pill,
  },
});
