import { Redirect, Stack } from 'expo-router';
import { useAuthStore } from '@/state/authStore';
import { useTheme } from '@/theme/ThemeProvider';

export default function AuthLayout() {
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
        animation: 'slide_from_right',
      }}
    />
  );
}
