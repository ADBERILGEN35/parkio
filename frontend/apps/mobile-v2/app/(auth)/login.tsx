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
import {
  clearPendingAuthGateIntent,
  peekPendingAuthGateIntent,
} from '@/features/auth/authGateIntents';
import { escapeToPublicExplore } from '@/features/auth/escapeToPublicExplore';
import { resolvePostLoginHref } from '@/features/auth/resolvePostLoginHref';
import {
  isRegistrationSignupAllowed,
  useRegistrationMode,
} from '@/features/auth/useRegistrationMode';
import { openGoogleMapsDirections } from '@/features/municipal/googleMapsDirections';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { authApi } from '@/services/api';
import { adoptSession, SessionPersistenceFailure } from '@/services/auth';
import { useTheme } from '@/theme/ThemeProvider';
import { useToast } from '@/providers/ToastProvider';

export default function LoginScreen() {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const router = useRouter();
  const toast = useToast();
  const registrationMode = useRegistrationMode();
  const signupAllowed = isRegistrationSignupAllowed(registrationMode);
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
      // Snapshot resume before adoptSession: AuthLayout may sync-redirect on setSession.
      const pending = peekPendingAuthGateIntent();
      const response = await authApi.login(parsed.data);
      await adoptSession(response);
      void applyPendingProfile(response.user.email);
      clearPendingAuthGateIntent();
      if (pending?.intent === 'google-maps') {
        const mapsResult = await openGoogleMapsDirections(pending.latitude, pending.longitude);
        if (mapsResult !== 'opened') {
          toast.show(t('map.municipal.openInMapsFailed'), 'error');
        }
      }
      router.replace(resolvePostLoginHref(pending));
    } catch (raw) {
      if (raw instanceof AccountNotVerifiedError) {
        setNotVerified(true);
        setError({ message: t('auth.login.notVerified'), traceId: null });
      } else if (raw instanceof SessionPersistenceFailure) {
        setError({ message: t('auth.login.persistenceFailed'), traceId: null });
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
      <Button
        label={t('auth.login.exploreWithoutAccount')}
        variant="ghost"
        onPress={() => escapeToPublicExplore(router)}
      />
      {signupAllowed ? (
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
      ) : (
        <Button
          label={t('auth.login.registrationAbout')}
          variant="ghost"
          onPress={() => router.push('/(auth)/register')}
        />
      )}
    </AuthScreen>
  );
}

const styles = StyleSheet.create({
  forgotRow: { alignItems: 'flex-end', marginTop: -6 },
  footerRow: { flexDirection: 'row', justifyContent: 'center', marginTop: 8 },
});
