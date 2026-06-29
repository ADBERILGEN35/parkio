import { Redirect, Stack } from 'expo-router';
import { useAuthStore } from '@/state/authStore';

/**
 * Auth group guard: a signed-in user has no business on login/register, so bounce
 * them into the app. While bootstrap is pending we render nothing (the root index
 * holds the splash).
 */
export default function AuthLayout() {
  const bootstrapPending = useAuthStore((s) => s.bootstrapPending);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);

  if (bootstrapPending) return null;
  if (isAuthenticated) return <Redirect href="/(main)/(tabs)/home" />;

  return <Stack screenOptions={{ headerShown: false }} />;
}
