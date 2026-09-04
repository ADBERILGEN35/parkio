import 'react-native-gesture-handler';
import {
  Inter_400Regular,
  Inter_500Medium,
  Inter_600SemiBold,
  Inter_700Bold,
  useFonts,
} from '@expo-google-fonts/inter';
import { Stack, useRouter } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { OfflineBanner } from '@/components/feedback/OfflineBanner';
import { AppProviders } from '@/providers/AppProviders';
// Importing the api module wires the single-flight refresh handler exactly once.
import '@/services/api';
import { bootstrapSession } from '@/services/auth';
import {
  addNotificationTapListener,
  configureForegroundHandling,
  guardNotificationRoute,
  registerForPushNotifications,
} from '@/services/pushNotifications';
import { useAuthStore } from '@/state/authStore';
import { useOnboardingStore } from '@/state/onboardingStore';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';
import { useMunicipalFilterStore } from '@/features/municipal/municipalFilterStore';
import { useTheme } from '@/theme/ThemeProvider';

// Foreground notifications render as a banner instead of being swallowed.
configureForegroundHandling();

// Keep the native splash up until fonts + session restore settle.
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
  const theme = useTheme();
  const [fontsLoaded] = useFonts({
    Inter_400Regular,
    Inter_500Medium,
    Inter_600SemiBold,
    Inter_700Bold,
  });
  const authStatus = useAuthStore((s) => s.status);
  const user = useAuthStore((s) => s.user);
  const onboardingHydrated = useOnboardingStore((s) => s.hydrated);

  useEffect(() => {
    void bootstrapSession();
    void useOnboardingStore.getState().hydrate();
    void useShareDraftStore.getState().hydrate();
    void useMunicipalFilterStore.getState().hydrate();
  }, []);

  const ready = fontsLoaded && authStatus !== 'bootstrapping' && onboardingHydrated;

  useEffect(() => {
    if (ready) {
      void SplashScreen.hideAsync();
    }
  }, [ready]);

  // Register the push token once a session exists (the endpoint is per-user).
  // Fails soft where remote push is unavailable (e.g. Expo Go).
  useEffect(() => {
    if (authStatus === 'authenticated') {
      void registerForPushNotifications();
    }
  }, [authStatus]);

  // Notification taps (including the cold-start one) deep-link into an
  // allow-listed route for the current role set.
  useEffect(
    () =>
      addNotificationTapListener((route) => {
        const guarded = guardNotificationRoute(route, user?.roles ?? []);
        if (guarded) {
          router.push(guarded);
        }
      }),
    [router, user],
  );

  if (!ready) {
    return null;
  }

  return (
    <>
      <StatusBar style={theme.mode === 'dark' ? 'light' : 'dark'} />
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: theme.colors.background },
        }}
      >
        <Stack.Screen name="index" />
        <Stack.Screen name="(onboarding)" />
        <Stack.Screen name="(auth)" />
        <Stack.Screen name="(main)" />
      </Stack>
      {/* Floating overlay pill — rendered after the Stack so it paints on top. */}
      <OfflineBanner />
    </>
  );
}
