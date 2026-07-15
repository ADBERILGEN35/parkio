import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { Redirect, Stack } from 'expo-router';
import { useAuthStore } from '@/state/authStore';
import { useTheme } from '@/theme';

/**
 * Protected area guard. Any deep link into a `(main)` route while signed out is
 * redirected to login; while bootstrap is pending we render nothing (root index
 * owns the splash). Feature routes live here as stack screens above the tabs.
 */
export default function MainLayout() {
  const bootstrapPending = useAuthStore((s) => s.bootstrapPending);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const theme = useTheme();

  if (bootstrapPending) {
    return (
      <View style={[styles.center, { backgroundColor: theme.colors.background }]}>
        <ActivityIndicator color={theme.colors.primary} />
      </View>
    );
  }
  if (!isAuthenticated) return <Redirect href="/(auth)/login" />;

  return (
    <Stack
      screenOptions={{
        headerShown: false,
        headerStyle: { backgroundColor: theme.colors.background },
        headerTintColor: theme.colors.text,
        headerTitleStyle: { fontSize: 16, fontWeight: '600', color: theme.colors.text },
        headerShadowVisible: false,
        contentStyle: { backgroundColor: theme.colors.background },
      }}
    >
      <Stack.Screen name="(tabs)" />
      <Stack.Screen name="upload" options={{ presentation: 'modal' }} />
      <Stack.Screen name="map" />
      <Stack.Screen name="smart-return" />
      <Stack.Screen name="my-spots" />
      <Stack.Screen name="leaderboard" />
      <Stack.Screen name="impact" />
      <Stack.Screen name="spots/[id]" />
      <Stack.Screen name="reports" />
      <Stack.Screen name="moderation" />
      <Stack.Screen name="analytics" />
      <Stack.Screen name="profile-edit" />
      <Stack.Screen name="vehicle" />
      <Stack.Screen name="preferences" />
      <Stack.Screen name="change-password" />
      <Stack.Screen name="about" />
    </Stack>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});