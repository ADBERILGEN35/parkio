import { Stack, useRouter } from 'expo-router';
import { Screen, StateView } from '@/components/ui';

/** PLACEHOLDER (M2). The real map experience is a later sprint. */
export default function MapPlaceholder() {
  const router = useRouter();
  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Find parking' }} />
      <Screen scroll={false}>
        <StateView
          glyph="🗺️"
          title="Map is coming soon"
          description="Live parking discovery on the map arrives in an upcoming release."
          actionLabel="Go back"
          onAction={() => router.back()}
        />
      </Screen>
    </>
  );
}
