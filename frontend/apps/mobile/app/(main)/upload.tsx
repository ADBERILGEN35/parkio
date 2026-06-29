import { Stack, useRouter } from 'expo-router';
import { Screen, StateView } from '@/components/ui';

/** PLACEHOLDER (M2). Camera + spot upload wizard is a later sprint. */
export default function UploadPlaceholder() {
  const router = useRouter();
  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Share a spot' }} />
      <Screen scroll={false}>
        <StateView
          glyph="📷"
          title="Sharing a spot is coming soon"
          description="Capturing and submitting a parking spot arrives in an upcoming release."
          actionLabel="Close"
          onAction={() => router.back()}
        />
      </Screen>
    </>
  );
}
