import { Redirect, Stack } from 'expo-router';
import { useAuthStore } from '@/state/authStore';
import { useTheme } from '@/theme/ThemeProvider';

/** Authenticated shell — everything inside requires a session. */
export default function MainLayout() {
  const theme = useTheme();
  const authStatus = useAuthStore((s) => s.status);

  if (authStatus === 'anonymous') {
    return <Redirect href="/(onboarding)/welcome" />;
  }

  return (
    <Stack
      screenOptions={{
        headerShown: false,
        contentStyle: { backgroundColor: theme.colors.background },
      }}
    >
      <Stack.Screen name="(tabs)" />
      <Stack.Screen name="share" options={{ presentation: 'fullScreenModal', animation: 'slide_from_bottom' }} />
      <Stack.Screen name="spots/[id]" options={{ animation: 'slide_from_right' }} />
    </Stack>
  );
}
