import { Redirect, Stack } from 'expo-router';
import { useAuthStore } from '@/state/authStore';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * Anonymous public product shell.
 * Authenticated users are sent to the private main map — do not weaken (main).
 */
export default function PublicLayout() {
  const theme = useTheme();
  const authStatus = useAuthStore((s) => s.status);

  if (authStatus === 'authenticated') {
    return <Redirect href="/(main)/(tabs)/map" />;
  }
  if (authStatus === 'suspended') {
    return <Redirect href="/(main)/suspended" />;
  }

  return (
    <Stack
      screenOptions={{
        headerShown: false,
        contentStyle: { backgroundColor: theme.colors.background },
      }}
    />
  );
}
