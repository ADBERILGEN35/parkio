import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Link, useRouter } from 'expo-router';
import { AccountNotVerifiedError } from '@parkio/api-client';
import { loginSchema } from '@parkio/validation';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { TextField } from '@/components/ui/TextField';
import { AuthScreen } from '@/features/auth/AuthScreen';
import { applyPendingProfile } from '@/features/auth/pendingProfile';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { authApi } from '@/services/api';
import { adoptSession } from '@/services/auth';
import { useTheme } from '@/theme/ThemeProvider';
import { useToast } from '@/providers/ToastProvider';

export default function LoginScreen() {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const router = useRouter();
  const toast = useToast();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [notVerified, setNotVerified] = useState(false);
  const [error, setError] = useState<{ message: string; traceId: string | null } | null>(null);

  const submit = async () => {
    const parsed = loginSchema.safeParse({ email: email.trim(), password });
    if (!parsed.success) {
      setError({ message: t('auth.login.invalid'), traceId: null });
      return;
    }
    setSubmitting(true);
    setError(null);
    setNotVerified(false);
    try {
      const response = await authApi.login(parsed.data);
      adoptSession(response);
      void applyPendingProfile(response.user.email);
      router.replace('/(main)/(tabs)/map');
    } catch (raw) {
      if (raw instanceof AccountNotVerifiedError) {
        setNotVerified(true);
        setError({ message: t('auth.login.notVerified'), traceId: null });
      } else {
        setError(describeApiError(raw, t));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const resendVerification = async () => {
    try {
      await authApi.resendVerification({ email: email.trim(), locale });
      toast.show(t('auth.checkEmail.resent'), 'success');
      router.push({ pathname: '/(auth)/check-email', params: { email: email.trim() } });
    } catch (raw) {
      toast.show(describeApiError(raw, t).message, 'error');
    }
  };

  return (
    <AuthScreen title={t('auth.login.title')}>
      <TextField
        label={t('auth.email')}
        placeholder={t('auth.emailPlaceholder')}
        autoCapitalize="none"
        autoComplete="email"
        keyboardType="email-address"
        value={email}
        onChangeText={setEmail}
      />
      <TextField
        label={t('auth.password')}
        password
        autoComplete="current-password"
        value={password}
        onChangeText={setPassword}
        error={error?.message ?? null}
        traceId={error?.traceId ?? null}
        onSubmitEditing={submit}
        returnKeyType="go"
      />
      {notVerified && (
        <Button
          label={t('auth.login.notVerifiedCta')}
          variant="tonal"
          size="md"
          onPress={resendVerification}
        />
      )}
      <View style={styles.forgotRow}>
        <Link href="/(auth)/forgot-password" asChild>
          <AppText variant="bodyMd" color={theme.colors.primary}>
            {t('auth.login.forgot')}
          </AppText>
        </Link>
      </View>
      <Button label={t('auth.login.cta')} onPress={submit} loading={submitting} />
      <View style={styles.footerRow}>
        <AppText variant="bodyMd" color={theme.colors.onSurfaceVariant}>
          {t('auth.login.noAccount')}{' '}
        </AppText>
        <Link href="/(auth)/register" replace asChild>
          <AppText variant="bodyMd" color={theme.colors.primary}>
            {t('auth.login.registerLink')}
          </AppText>
        </Link>
      </View>
    </AuthScreen>
  );
}

const styles = StyleSheet.create({
  forgotRow: { alignItems: 'flex-end', marginTop: -6 },
  footerRow: { flexDirection: 'row', justifyContent: 'center', marginTop: 8 },
});
