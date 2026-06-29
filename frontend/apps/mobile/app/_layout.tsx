import { Stack } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { OfflineBanner } from '@/components/feedback/OfflineBanner';
import { AppProviders } from '@/providers/AppProviders';
// Importing the api module wires the single-flight refresh handler exactly once.
import '@/services/api';
import { bootstrapSession } from '@/services/auth';
import { useAuthStore } from '@/state/authStore';

// Keep the native splash up until the cold-start session restore settles.
void SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  return (
    <AppProviders>
      <RootNavigator />
    </AppProviders>
  );
}

function RootNavigator() {
  const bootstrapPending = useAuthStore((s) => s.bootstrapPending);

  useEffect(() => {
    void bootstrapSession();
  }, []);

  useEffect(() => {
    if (!bootstrapPending) {
      void SplashScreen.hideAsync();
    }
  }, [bootstrapPending]);

  return (
    <>
      <StatusBar style="auto" />
      <OfflineBanner />
      <Stack screenOptions={{ headerShown: false }}>
        <Stack.Screen name="index" />
        <Stack.Screen name="(auth)" />
        <Stack.Screen name="(main)" />
      </Stack>
    </>
  );
}
