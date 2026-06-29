import { Redirect, Stack } from 'expo-router';
import { useAuthStore } from '@/state/authStore';

/**
 * Protected area guard. Any deep link into a `(main)` route while signed out is
 * redirected to login; while bootstrap is pending we render nothing (root index
 * owns the splash). Placeholder feature routes (upload/map/smart-return) live
 * here as modal-capable stack screens above the tabs.
 */
export default function MainLayout() {
  const bootstrapPending = useAuthStore((s) => s.bootstrapPending);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);

  if (bootstrapPending) return null;
  if (!isAuthenticated) return <Redirect href="/(auth)/login" />;

  return (
    <Stack screenOptions={{ headerShown: false }}>
      <Stack.Screen name="(tabs)" />
      <Stack.Screen name="upload" options={{ presentation: 'modal' }} />
      <Stack.Screen name="map" />
      <Stack.Screen name="smart-return" />
    </Stack>
  );
}
