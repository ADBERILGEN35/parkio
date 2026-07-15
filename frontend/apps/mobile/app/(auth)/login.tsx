import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useRouter } from 'expo-router';
import { useForm } from 'react-hook-form';
import { useState } from 'react';
import { KeyboardAvoidingView, Platform, StyleSheet, View } from 'react-native';
import { loginSchema, type LoginFormValues } from '@parkio/validation';
import { BrandLockup } from '@/components/brand/BrandLockup';
import { Button, Screen } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { FormTextField } from '@/components/forms/FormTextField';
import { useAuth } from '@/hooks/useAuth';
import { useLocale } from '@/i18n/LocaleProvider';
import { useToast } from '@/providers/ToastProvider';
import { track } from '@/services/analytics';
import { toUserMessage } from '@/utils/errors';

export default function LoginScreen() {
  const { login } = useAuth();
  const router = useRouter();
  const toast = useToast();
  const { t } = useLocale();
  const [submitting, setSubmitting] = useState(false);

  const { control, handleSubmit } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
  });

  const onSubmit = handleSubmit(async (values) => {
    setSubmitting(true);
    try {
      await login(values);
      track('login');
      router.replace('/(main)/(tabs)/map');
    } catch (error) {
      toast.showError(toUserMessage(error));
    } finally {
      setSubmitting(false);
    }
  });

  return (
    <Screen contentStyle={styles.content} edges={['top', 'left', 'right', 'bottom']}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={styles.flex}
        keyboardVerticalOffset={24}
      >
        <View style={styles.header}>
          <BrandLockup showTagline />
          <AppText variant="display">{t('Welcome back')}</AppText>
          <AppText variant="body" tone="muted">
            {t('Sign in to find and share parking.')}
          </AppText>
        </View>

        <View style={styles.form}>
          <FormTextField
            control={control}
            name="email"
            label={t('Email')}
            testID="login.email"
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
            testID="login.password"
            secureTextEntry
            autoComplete="current-password"
            textContentType="password"
            returnKeyType="done"
            onSubmitEditing={onSubmit}
            placeholder={t('Your password')}
          />

          <Link href="/(auth)/forgot-password" style={styles.forgot}>
            <AppText variant="label" tone="primary">
              {t('Forgot password?')}
            </AppText>
          </Link>

          <Button label={t('Sign in')} testID="login.submit" onPress={onSubmit} loading={submitting} />
        </View>

        <View style={styles.footer}>
          <AppText variant="body" tone="muted">
            {t('New to Parkio?')}{' '}
          </AppText>
          <Link href="/(auth)/register">
            <AppText variant="label" tone="primary">
              {t('Create an account')}
            </AppText>
          </Link>
        </View>
      </KeyboardAvoidingView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1, justifyContent: 'center', gap: 32 },
  content: { justifyContent: 'center', gap: 32 },
  header: { gap: 8 },
  form: { gap: 16 },
  forgot: { alignSelf: 'flex-end' },
  footer: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', flexWrap: 'wrap' },
});