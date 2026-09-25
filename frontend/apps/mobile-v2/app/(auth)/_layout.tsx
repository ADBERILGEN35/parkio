import { Redirect, Stack } from 'expo-router';
import { peekPendingAuthGateIntent } from '@/features/auth/authGateIntents';
import { resolvePostLoginHref } from '@/features/auth/resolvePostLoginHref';
import { useAuthStore } from '@/state/authStore';
import { useTheme } from '@/theme/ThemeProvider';

export default function AuthLayout() {
  const theme = useTheme();
  const authStatus = useAuthStore((s) => s.status);

  if (authStatus === 'authenticated') {
    // Honor pending AuthGate resume (e.g. facility-detail) instead of always map.
    // Peek only — login clears after a successful adoptSession.
    return <Redirect href={resolvePostLoginHref(peekPendingAuthGateIntent())} />;
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
