import { useLocalSearchParams, useRouter } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Button, Screen } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormTextField } from '@/components/forms/FormTextField';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { resendVerification } from '@/services/auth';
import { useToast } from '@/providers/ToastProvider';
import { toUserMessage } from '@/utils/errors';

const schema = z.object({ email: z.string().email('Enter a valid email') });
type Values = z.infer<typeof schema>;

export default function CheckEmailScreen() {
  const router = useRouter();
  const toast = useToast();
  const params = useLocalSearchParams<{ email?: string }>();
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const { control, handleSubmit } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { email: typeof params.email === 'string' ? params.email : '' },
  });

  const onResend = handleSubmit(async (values) => {
    setSubmitting(true);
    setMessage(null);
    try {
      await resendVerification(values.email.trim());
      setMessage('Verification email sent. Please check your inbox.');
      toast.showSuccess('Verification email sent.');
    } catch (error) {
      toast.showError(toUserMessage(error));
    } finally {
      setSubmitting(false);
    }
  });

  return (
    <Screen contentStyle={styles.content}>
      <View style={styles.header}>
        <AppText variant="display">Check your email</AppText>
        <AppText variant="body" tone="muted">
          We sent a verification link to your email address. Open it to activate your Parkio account.
        </AppText>
      </View>
      <View style={styles.form}>
        <FormTextField
          control={control}
          name="email"
          label="Email"
          autoCapitalize="none"
          keyboardType="email-address"
          autoComplete="email"
          textContentType="emailAddress"
        />
        {message ? (
          <AppText variant="body" tone="success">
            {message}
          </AppText>
        ) : null}
        <Button label="Resend verification" onPress={onResend} loading={submitting} />
        <Button label="Back to sign in" variant="ghost" onPress={() => router.replace('/(auth)/login')} />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { justifyContent: 'center', gap: 32 },
  header: { gap: 8 },
  form: { gap: 16 },
});