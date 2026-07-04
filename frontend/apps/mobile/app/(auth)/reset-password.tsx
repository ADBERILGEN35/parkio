import { zodResolver } from '@hookform/resolvers/zod';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useForm, useWatch } from 'react-hook-form';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import {
  passwordRequirements,
  passwordRequirementState,
  resetPasswordSchema,
  type ResetPasswordFormValues,
} from '@parkio/validation';
import { Button, Screen } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormTextField } from '@/components/forms/FormTextField';
import { resetPassword } from '@/services/auth';
import { useToast } from '@/providers/ToastProvider';
import { toUserMessage } from '@/utils/errors';

export default function ResetPasswordScreen() {
  const router = useRouter();
  const toast = useToast();
  const params = useLocalSearchParams<{ token?: string }>();
  const token = typeof params.token === 'string' ? params.token : '';
  const [submitting, setSubmitting] = useState(false);

  const { control, handleSubmit } = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { password: '', confirmPassword: '' },
    mode: 'onChange',
  });
  const password = useWatch({ control, name: 'password' }) ?? '';
  const requirementState = passwordRequirementState(password);

  const onSubmit = handleSubmit(async (values) => {
    if (!token) {
      toast.showError('Password reset link is missing a token.');
      return;
    }
    setSubmitting(true);
    try {
      await resetPassword(token, values.password);
      toast.showSuccess('Password reset. Sign in with your new password.');
      router.replace('/(auth)/login');
    } catch (error) {
      toast.showError(toUserMessage(error));
    } finally {
      setSubmitting(false);
    }
  });

  return (
    <Screen contentStyle={styles.content}>
      <View style={styles.header}>
        <AppText variant="display">Choose a new password</AppText>
        <AppText variant="body" tone="muted">
          Use a strong password you do not use elsewhere.
        </AppText>
      </View>
      {!token ? (
        <AppText variant="body" tone="danger">
          Password reset link is missing a token.
        </AppText>
      ) : (
        <View style={styles.form}>
          <FormTextField
            control={control}
            name="password"
            label="New password"
            secureTextEntry
            autoComplete="new-password"
            textContentType="newPassword"
          />
          <FormTextField
            control={control}
            name="confirmPassword"
            label="Confirm password"
            secureTextEntry
            autoComplete="new-password"
            textContentType="newPassword"
          />
          <View style={styles.requirements}>
            {passwordRequirements.map((req) => {
              const met = requirementState[req.id];
              return (
                <AppText key={req.id} variant="caption" tone={met ? 'success' : 'muted'}>
                  {met ? '✓' : '○'} {req.label}
                </AppText>
              );
            })}
          </View>
          <Button label="Reset password" onPress={onSubmit} loading={submitting} />
        </View>
      )}
      <Button label="Back to sign in" variant="ghost" onPress={() => router.replace('/(auth)/login')} />
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { justifyContent: 'center', gap: 24 },
  header: { gap: 8 },
  form: { gap: 16 },
  requirements: { gap: 4 },
});