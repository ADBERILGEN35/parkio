import { zodResolver } from '@hookform/resolvers/zod';
import {
  changePasswordSchema,
  passwordRequirementState,
  passwordRequirements,
  type ChangePasswordFormValues,
} from '@parkio/validation';
import { Stack, useRouter } from 'expo-router';
import { useForm, useWatch } from 'react-hook-form';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Button, Card, Screen } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormTextField } from '@/components/forms/FormTextField';
import { changePassword } from '@/services/auth';
import { useToast } from '@/providers/ToastProvider';
import { toUserMessage } from '@/utils/errors';

export default function ChangePasswordScreen() {
  const router = useRouter();
  const toast = useToast();
  const [submitting, setSubmitting] = useState(false);

  const { control, handleSubmit, reset } = useForm<ChangePasswordFormValues>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: '', password: '', confirmPassword: '' },
    mode: 'onChange',
  });
  const password = useWatch({ control, name: 'password' }) ?? '';
  const requirementState = passwordRequirementState(password);

  const onSubmit = handleSubmit(async (values) => {
    setSubmitting(true);
    try {
      await changePassword(values.currentPassword, values.password);
      reset();
      toast.showSuccess('Password changed.');
      router.back();
    } catch (error) {
      toast.showError(toUserMessage(error));
    } finally {
      setSubmitting(false);
    }
  });

  return (
    <>
      <Stack.Screen options={{ headerShown: true, title: 'Change password' }} />
      <Screen contentStyle={styles.content} edges={['left', 'right', 'bottom']}>
        <Card>
          <AppText variant="body" tone="muted">
            Choose a strong password you do not use elsewhere.
          </AppText>
          <View style={styles.form}>
            <FormTextField
              control={control}
              name="currentPassword"
              label="Current password"
              secureTextEntry
              autoComplete="password"
              textContentType="password"
            />
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
              label="Confirm new password"
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
            <Button label="Update password" onPress={onSubmit} loading={submitting} />
          </View>
        </Card>
      </Screen>
    </>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  form: { gap: 12, marginTop: 12 },
  requirements: { gap: 4 },
});