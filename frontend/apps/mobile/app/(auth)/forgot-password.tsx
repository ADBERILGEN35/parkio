import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter } from 'expo-router';
import { useForm } from 'react-hook-form';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { forgotPasswordSchema, type ForgotPasswordFormValues } from '@parkio/validation';
import { Button, Screen } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormTextField } from '@/components/forms/FormTextField';
import { requestPasswordReset } from '@/services/auth';
import { useToast } from '@/providers/ToastProvider';
import { toUserMessage } from '@/utils/errors';

/**
 * Forgot-password (M1 placeholder flow): collects the email and calls the existing
 * backend endpoint, then confirms. The full reset-token deep-link flow is a later
 * sprint — we always show a neutral confirmation to avoid leaking which emails exist.
 */
export default function ForgotPasswordScreen() {
  const router = useRouter();
  const toast = useToast();
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);

  const { control, handleSubmit } = useForm<ForgotPasswordFormValues>({
    resolver: zodResolver(forgotPasswordSchema),
    defaultValues: { email: '' },
  });

  const onSubmit = handleSubmit(async (values) => {
    setSubmitting(true);
    try {
      await requestPasswordReset(values.email);
      setSent(true);
    } catch (error) {
      toast.showError(toUserMessage(error));
    } finally {
      setSubmitting(false);
    }
  });

  return (
    <Screen contentStyle={styles.content}>
      <View style={styles.header}>
        <AppText variant="display">Reset password</AppText>
        <AppText variant="body" tone="muted">
          {sent
            ? 'If an account exists for that email, we’ve sent reset instructions.'
            : 'Enter your email and we’ll send reset instructions.'}
        </AppText>
      </View>

      {sent ? (
        <Button label="Back to sign in" onPress={() => router.replace('/(auth)/login')} />
      ) : (
        <View style={styles.form}>
          <FormTextField
            control={control}
            name="email"
            label="Email"
            autoCapitalize="none"
            autoComplete="email"
            keyboardType="email-address"
            textContentType="emailAddress"
            placeholder="you@example.com"
          />
          <Button label="Send instructions" onPress={onSubmit} loading={submitting} />
          <Button label="Cancel" variant="ghost" onPress={() => router.back()} />
        </View>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { justifyContent: 'center', gap: 28 },
  header: { gap: 8 },
  form: { gap: 16 },
});
