import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useRouter } from 'expo-router';
import { useForm } from 'react-hook-form';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { loginSchema, type LoginFormValues } from '@parkio/validation';
import { Button, Screen } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormTextField } from '@/components/forms/FormTextField';
import { useAuth } from '@/hooks/useAuth';
import { useToast } from '@/providers/ToastProvider';
import { toUserMessage } from '@/utils/errors';

export default function LoginScreen() {
  const { login } = useAuth();
  const router = useRouter();
  const toast = useToast();
  const [submitting, setSubmitting] = useState(false);

  const { control, handleSubmit } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
  });

  const onSubmit = handleSubmit(async (values) => {
    setSubmitting(true);
    try {
      await login(values);
      router.replace('/(main)/(tabs)/home');
    } catch (error) {
      toast.showError(toUserMessage(error));
    } finally {
      setSubmitting(false);
    }
  });

  return (
    <Screen contentStyle={styles.content}>
      <View style={styles.header}>
        <AppText variant="display">Welcome back</AppText>
        <AppText variant="body" tone="muted">
          Sign in to find and share parking.
        </AppText>
      </View>

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
        <FormTextField
          control={control}
          name="password"
          label="Password"
          secureTextEntry
          autoComplete="current-password"
          textContentType="password"
          placeholder="Your password"
        />

        <Link href="/(auth)/forgot-password" style={styles.forgot}>
          <AppText variant="label" tone="primary">
            Forgot password?
          </AppText>
        </Link>

        <Button label="Sign in" onPress={onSubmit} loading={submitting} />
      </View>

      <View style={styles.footer}>
        <AppText variant="body" tone="muted">
          New to Parkio?{' '}
        </AppText>
        <Link href="/(auth)/register">
          <AppText variant="label" tone="primary">
            Create an account
          </AppText>
        </Link>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { justifyContent: 'center', gap: 32 },
  header: { gap: 8 },
  form: { gap: 16 },
  forgot: { alignSelf: 'flex-end' },
  footer: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', flexWrap: 'wrap' },
});
