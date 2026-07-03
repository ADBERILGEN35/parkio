import { Stack, useRouter } from 'expo-router';
import { Screen, StateView } from '@/components/ui';

export default function NotFound() {
  const router = useRouter();
  return (
    <>
      <Stack.Screen options={{ title: 'Not found' }} />
      <Screen scroll={false}>
        <StateView
          icon="compass-outline"
          title="This screen doesn’t exist"
          description="The page you’re looking for isn’t here."
          actionLabel="Go to home"
          onAction={() => router.replace('/')}
        />
      </Screen>
    </>
  );
}
