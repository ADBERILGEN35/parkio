import { Stack, useRouter } from 'expo-router';
import { useEffect } from 'react';
import { Screen, StateView } from '@/components/ui';
import { track } from '@/services/analytics';

/** PLACEHOLDER (M3). Smart Return mobile UI is a later sprint (web V1 already ships). */
export default function SmartReturnPlaceholder() {
  const router = useRouter();
  useEffect(() => track('smart_return_opened'), []);
  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Smart Return' }} />
      <Screen scroll={false}>
        <StateView
          icon="home-outline"
          title="Smart Return is coming to mobile"
          description="Your one parking check before heading home will be available here soon."
          actionLabel="Go back"
          onAction={() => router.back()}
        />
      </Screen>
    </>
  );
}
