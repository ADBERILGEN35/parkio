import { Link, Stack } from 'expo-router';
import { Screen, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';

export default function NotFound() {
  return (
    <>
      <Stack.Screen options={{ title: 'Not found' }} />
      <Screen scroll={false}>
        <StateView
          glyph="🧭"
          title="This screen doesn’t exist"
          description="The page you’re looking for isn’t here."
        >
          <Link href="/" style={{ marginTop: 16 }}>
            <AppText variant="label" tone="primary">
              Go to home
            </AppText>
          </Link>
        </StateView>
      </Screen>
    </>
  );
}
