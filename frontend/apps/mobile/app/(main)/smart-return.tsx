import { Stack, useRouter } from 'expo-router';
import { Screen, StateView } from '@/components/ui';

/** PLACEHOLDER (M3). Smart Return mobile UI is a later sprint (web V1 already ships). */
export default function SmartReturnPlaceholder() {
  const router = useRouter();
  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Smart Return' }} />
      <Screen scroll={false}>
        <StateView
          glyph="🏠"
          title="Smart Return is coming to mobile"
          description="Your one parking check before heading home will be available here soon."
          actionLabel="Go back"
          onAction={() => router.back()}
        />
      </Screen>
    </>
  );
}
