import { Redirect } from 'expo-router';
import { useAuthStore } from '@/state/authStore';
import { useOnboardingStore } from '@/state/onboardingStore';

/**
 * Entry router. Root layout blocks render until bootstrap settles, so the
 * decision here is synchronous: first-run → onboarding; signed-in → map;
 * suspended → the suspended wall; otherwise → welcome/auth.
 */
export default function Index() {
  const authStatus = useAuthStore((s) => s.status);
  const onboardingCompleted = useOnboardingStore((s) => s.completed);

  if (authStatus === 'suspended') {
    return <Redirect href="/(main)/suspended" />;
  }
  if (authStatus === 'authenticated') {
    return <Redirect href="/(main)/(tabs)/map" />;
  }
  if (!onboardingCompleted) {
    return <Redirect href="/(onboarding)/language" />;
  }
  return <Redirect href="/(onboarding)/welcome" />;
}
