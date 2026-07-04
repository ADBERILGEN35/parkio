import { useLocalSearchParams, useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Button, Screen } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { verifyEmail } from '@/services/auth';
import { toUserMessage } from '@/utils/errors';

type State = 'verifying' | 'success' | 'error';

export default function VerifyEmailScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ token?: string }>();
  const token = typeof params.token === 'string' ? params.token : '';
  const [state, setState] = useState<State>(token ? 'verifying' : 'error');
  const [error, setError] = useState<string | null>(
    token ? null : 'Verification link is invalid or expired.',
  );

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    verifyEmail(token)
      .then(() => {
        if (!cancelled) setState('success');
      })
      .catch((err) => {
        if (cancelled) return;
        setError(toUserMessage(err));
        setState('error');
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  return (
    <Screen contentStyle={styles.content}>
      <View style={styles.header}>
        <AppText variant="display">{state === 'success' ? 'Email verified' : 'Verify your email'}</AppText>
        <AppText variant="body" tone="muted">
          {state === 'success'
            ? 'Your account is ready for sign in.'
            : state === 'verifying'
              ? 'Checking your link…'
              : 'We could not verify this link.'}
        </AppText>
      </View>
      {state === 'error' && error ? (
        <AppText variant="body" tone="danger">
          {error}
        </AppText>
      ) : null}
      {state === 'success' ? (
        <Button label="Sign in" onPress={() => router.replace('/(auth)/login')} />
      ) : null}
      {state === 'error' ? (
        <Button label="Request a new link" onPress={() => router.replace('/(auth)/check-email')} />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { justifyContent: 'center', gap: 24 },
  header: { gap: 8 },
});