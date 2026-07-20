import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { PressableScale } from '@/components/ui/PressableScale';
import { useT } from '@/i18n/LocaleProvider';
import type { TranslationKey } from '@/i18n/translations';
import { shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

type IconName = keyof typeof MaterialCommunityIcons.glyphMap;

const TAB_META: Record<string, { icon: IconName; activeIcon: IconName; labelKey: TranslationKey }> = {
  map: { icon: 'map-outline', activeIcon: 'map', labelKey: 'tabs.map' },
  'my-spots': { icon: 'car-outline', activeIcon: 'car', labelKey: 'tabs.mySpots' },
  leaderboard: { icon: 'podium', activeIcon: 'podium', labelKey: 'tabs.leaderboard' },
  profile: { icon: 'account-circle-outline', activeIcon: 'account-circle', labelKey: 'tabs.profile' },
};

interface TabRoute {
  key: string;
  name: string;
}

/**
 * Structural subset of react-navigation's BottomTabBarProps — the package is a
 * transitive dep of expo-router and not directly resolvable under pnpm's
 * strict layout, so the tab bar types only what it actually reads.
 */
export interface AppTabBarProps {
  state: { index: number; routes: TabRoute[] };
  navigation: {
    emit: (event: { type: 'tabPress'; target: string; canPreventDefault: true }) => {
      defaultPrevented: boolean;
    };
    navigate: (name: string) => void;
  };
  /** Center raised "Paylaş" action. */
  onSharePress: () => void;
}

/**
 * Custom 5-slot tab bar per the pen design: Harita · Yerlerim · [Paylaş raised
 * primary circle] · Liderlik · Profil. Surface bar with a hairline top edge.
 */
export function AppTabBar({ state, navigation, onSharePress }: AppTabBarProps) {
  const theme = useTheme();
  const t = useT();
  const insets = useSafeAreaInsets();
  const { colors } = theme;

  const routes = state.routes.filter((route) => route.name in TAB_META);
  const leftRoutes = routes.slice(0, 2);
  const rightRoutes = routes.slice(2);

  const renderTab = (route: (typeof routes)[number]) => {
    const meta = TAB_META[route.name];
    const routeIndex = state.routes.findIndex((r) => r.key === route.key);
    const focused = state.index === routeIndex;
    const color = focused
      ? theme.mode === 'dark'
        ? colors.primaryFixedDim
        : colors.primary
      : colors.onSurfaceVariant;

    return (
      <PressableScale
        key={route.key}
        scaleTo={0.92}
        accessibilityRole="tab"
        accessibilityLabel={t(meta.labelKey)}
        accessibilityState={{ selected: focused }}
        onPress={() => {
          const event = navigation.emit({ type: 'tabPress', target: route.key, canPreventDefault: true });
          if (!focused && !event.defaultPrevented) {
            navigation.navigate(route.name);
          }
        }}
        style={styles.tab}
      >
        <MaterialCommunityIcons name={focused ? meta.activeIcon : meta.icon} size={23} color={color} />
        <AppText variant="labelSm" color={color} numberOfLines={1}>
          {t(meta.labelKey)}
        </AppText>
      </PressableScale>
    );
  };

  return (
    <View
      style={[
        styles.bar,
        {
          backgroundColor: colors.surface,
          borderTopColor: colors.outlineVariant,
          paddingBottom: Math.max(insets.bottom, 8),
        },
      ]}
    >
      {leftRoutes.map(renderTab)}
      <View style={styles.shareSlot}>
        <PressableScale
          scaleTo={0.9}
          accessibilityRole="button"
          accessibilityLabel={t('tabs.share')}
          onPress={onSharePress}
          style={[styles.shareButton, { backgroundColor: colors.primary }, shadows.blueGlow]}
        >
          <MaterialCommunityIcons name="camera-plus-outline" size={26} color={colors.onPrimary} />
        </PressableScale>
        <AppText variant="labelSm" color={colors.onSurfaceVariant}>
          {t('tabs.share')}
        </AppText>
      </View>
      {rightRoutes.map(renderTab)}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingTop: 8,
    paddingHorizontal: 4,
  },
  tab: {
    flex: 1,
    alignItems: 'center',
    gap: 3,
    paddingVertical: 4,
  },
  shareSlot: {
    flex: 1,
    alignItems: 'center',
    gap: 3,
    marginTop: -26,
  },
  shareButton: {
    width: 54,
    height: 54,
    borderRadius: 27,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
