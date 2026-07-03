import { Stack, useRouter } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { OfflineBanner } from '@/components/feedback/OfflineBanner';
import { AppProviders } from '@/providers/AppProviders';
// Importing the api module wires the single-flight refresh handler exactly once.
import '@/services/api';
import { bootstrapSession } from '@/services/auth';
import { installCrashReporting } from '@/services/crashReporting';
import {
  addNotificationTapListener,
  configureForegroundHandling,
  registerForPushNotifications,
} from '@/services/pushNotifications';
import { useAuthStore } from '@/state/authStore';

// Crash handlers must exist before the first render can throw.
installCrashReporting();
// Foreground notifications render as a banner instead of being swallowed.
configureForegroundHandling();

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
  const router = useRouter();
  const bootstrapPending = useAuthStore((s) => s.bootstrapPending);
  const authenticated = useAuthStore((s) => s.user !== null);

  useEffect(() => {
    void bootstrapSession();
  }, []);

  useEffect(() => {
    if (!bootstrapPending) {
      void SplashScreen.hideAsync();
    }
  }, [bootstrapPending]);

  // Register the push token once a session exists (the endpoint is per-user).
  // Fails soft where remote push is unavailable (Expo Go on Android).
  useEffect(() => {
    if (!bootstrapPending && authenticated) {
      void registerForPushNotifications();
    }
  }, [bootstrapPending, authenticated]);

  // Notification taps (including the one that cold-started the app) deep-link
  // into an allow-listed route.
  useEffect(() => addNotificationTapListener((route) => router.push(route)), [router]);

  return (
    <>
      <StatusBar style="auto" />
      <Stack screenOptions={{ headerShown: false }}>
        <Stack.Screen name="index" />
        <Stack.Screen name="(auth)" />
        <Stack.Screen name="(main)" />
      </Stack>
      {/* Floating overlay pill — rendered after the Stack so it paints on top. */}
      <OfflineBanner />
    </>
  );
}
