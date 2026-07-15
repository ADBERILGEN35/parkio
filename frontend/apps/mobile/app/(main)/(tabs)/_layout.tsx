import { Ionicons } from '@expo/vector-icons';
import { Tabs, useRouter } from 'expo-router';
import { useTheme } from '@/theme';
import { useLocale } from '@/i18n/LocaleProvider';

/**
 * Bottom tabs — map-first IA aligned with web MobileNav:
 * Map · My spots · Share · Leaderboard · Profile
 * Notifications live under Profile (unread reachable from there).
 */
export default function TabsLayout() {
  const theme = useTheme();
  const { t } = useLocale();
  const router = useRouter();

  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: theme.colors.primary,
        tabBarInactiveTintColor: theme.colors.textMuted,
        tabBarStyle: {
          backgroundColor: theme.scheme === 'dark' ? theme.colors.surface : 'rgba(248, 249, 255, 0.94)',
          borderTopColor: theme.colors.border,
        },
        tabBarLabelStyle: { fontSize: 11, fontWeight: '500' },
        tabBarHideOnKeyboard: true,
      }}
    >
      <Tabs.Screen
        name="map"
        options={{
          title: t('Map'),
          tabBarAccessibilityLabel: t('Map'),
          tabBarIcon: ({ color, size }) => <Ionicons name="map-outline" color={color} size={size} />,
        }}
      />
      <Tabs.Screen
        name="my-spots"
        options={{
          title: t('My spots'),
          tabBarAccessibilityLabel: t('My spots'),
          tabBarIcon: ({ color, size }) => <Ionicons name="bookmark-outline" color={color} size={size} />,
        }}
      />
      <Tabs.Screen
        name="share"
        options={{
          title: t('Share'),
          tabBarAccessibilityLabel: t('Share a spot'),
          tabBarIcon: ({ color, size }) => <Ionicons name="add-circle-outline" color={color} size={size} />,
        }}
        listeners={{
          tabPress: (e) => {
            e.preventDefault();
            router.push('/(main)/upload');
          },
        }}
      />
      <Tabs.Screen
        name="leaderboard"
        options={{
          title: t('Leaderboard'),
          tabBarAccessibilityLabel: t('Leaderboard'),
          tabBarIcon: ({ color, size }) => <Ionicons name="trophy-outline" color={color} size={size} />,
        }}
      />
      <Tabs.Screen
        name="profile"
        options={{
          title: t('Profile'),
          tabBarAccessibilityLabel: t('Profile'),
          tabBarIcon: ({ color, size }) => <Ionicons name="person-outline" color={color} size={size} />,
        }}
      />
      {/* Legacy / deep-link routes kept but hidden from the tab bar */}
      <Tabs.Screen name="home" options={{ href: null }} />
      <Tabs.Screen name="notifications" options={{ href: null }} />
    </Tabs>
  );
}
