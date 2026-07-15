import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useRouter } from 'expo-router';
import { useForm, useWatch } from 'react-hook-form';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import {
  passwordRequirements,
  passwordRequirementState,
  registerSchema,
  type RegisterFormValues,
} from '@parkio/validation';
import { BrandLockup } from '@/components/brand/BrandLockup';
import { Button, Screen } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormTextField } from '@/components/forms/FormTextField';
import { useAuth } from '@/hooks/useAuth';
import { useLocale } from '@/i18n/LocaleProvider';
import { useToast } from '@/providers/ToastProvider';
import { track } from '@/services/analytics';
import { toUserMessage } from '@/utils/errors';

export default function RegisterScreen() {
  const { register } = useAuth();
  const router = useRouter();
  const toast = useToast();
  const { t } = useLocale();
  const [submitting, setSubmitting] = useState(false);

  const { control, handleSubmit } = useForm<RegisterFormValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { email: '', password: '' },
    mode: 'onChange',
  });

  const password = useWatch({ control, name: 'password' }) ?? '';
  const requirementState = passwordRequirementState(password);

  const onSubmit = handleSubmit(async (values) => {
    setSubmitting(true);
    try {
      await register(values);
      track('sign_up');
      router.replace({
        pathname: '/(auth)/check-email',
        params: { email: values.email },
      });
    } catch (error) {
      toast.showError(toUserMessage(error));
    } finally {
      setSubmitting(false);
    }
  });

  return (
    <Screen contentStyle={styles.content}>
      <View style={styles.header}>
        <BrandLockup />
        <AppText variant="display">{t('Create account')}</AppText>
        <AppText variant="body" tone="muted">
          {t('Join Parkio to find parking faster.')}
        </AppText>
      </View>

      <View style={styles.form}>
        <FormTextField
          control={control}
          name="email"
          label={t('Email')}
          autoCapitalize="none"
          autoComplete="email"
          keyboardType="email-address"
          textContentType="emailAddress"
          placeholder={t('you@example.com')}
        />
        <FormTextField
          control={control}
          name="password"
          label={t('Password')}
          secureTextEntry
          autoComplete="new-password"
          textContentType="newPassword"
          placeholder={t('At least 12 characters')}
        />

        <View style={styles.requirements} accessibilityLabel="Password requirements">
          {passwordRequirements.map((req) => {
            const met = requirementState[req.id];
            return (
              <AppText key={req.id} variant="caption" tone={met ? 'success' : 'muted'}>
                {met ? '✓' : '○'} {req.label}
              </AppText>
            );
          })}
        </View>

        <Button label="Create account" onPress={onSubmit} loading={submitting} />
      </View>

      <View style={styles.footer}>
        <AppText variant="body" tone="muted">
          Already have an account?{' '}
        </AppText>
        <Link href="/(auth)/login">
          <AppText variant="label" tone="primary">
            Sign in
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
  requirements: { gap: 4 },
  footer: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', flexWrap: 'wrap' },
});
