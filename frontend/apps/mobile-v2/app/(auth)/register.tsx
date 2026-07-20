import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Link, useRouter } from 'expo-router';
import { isStrongPassword, registerSchema } from '@parkio/validation';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Checkbox } from '@/components/ui/Checkbox';
import { TextField } from '@/components/ui/TextField';
import { AuthScreen } from '@/features/auth/AuthScreen';
import { PasswordChecklist } from '@/features/auth/PasswordChecklist';
import { stashPendingProfile } from '@/features/auth/pendingProfile';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { authApi } from '@/services/api';
import { useTheme } from '@/theme/ThemeProvider';

export default function RegisterScreen() {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const router = useRouter();
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [consent, setConsent] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<{ message: string; traceId: string | null } | null>(null);
  const [consentError, setConsentError] = useState(false);
  const [nameError, setNameError] = useState<string | null>(null);

  const submit = async () => {
    setError(null);
    setConsentError(false);
    setNameError(null);

    const trimmedName = displayName.trim();
    if (trimmedName.length < 2 || trimmedName.length > 50) {
      setNameError(t('common.requiredField'));
      return;
    }
    const parsed = registerSchema.safeParse({ email: email.trim(), password });
    if (!parsed.success || !isStrongPassword(password)) {
      setError({ message: t('common.error.generic'), traceId: null });
      return;
    }
    if (!consent) {
      setConsentError(true);
      return;
    }

    setSubmitting(true);
    try {
      await authApi.register({ ...parsed.data, locale });
      // Display name is applied via PATCH /users/me after the first login
      // (registration alone cannot authenticate — email must be verified).
      await stashPendingProfile({ email: parsed.data.email, displayName: trimmedName });
      router.replace({ pathname: '/(auth)/check-email', params: { email: parsed.data.email } });
    } catch (raw) {
      setError(describeApiError(raw, t));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthScreen title={t('auth.register.title')} subtitle={t('auth.register.verifyNote')}>
      <TextField
        label={t('profile.account.displayName')}
        placeholder={t('profile.account.displayNamePlaceholder')}
        value={displayName}
        onChangeText={setDisplayName}
        error={nameError}
        autoComplete="name"
      />
      <TextField
        label={t('auth.email')}
        placeholder={t('auth.emailPlaceholder')}
        autoCapitalize="none"
        autoComplete="email"
        keyboardType="email-address"
        value={email}
        onChangeText={setEmail}
      />
      <View style={styles.passwordBlock}>
        <TextField
          label={t('auth.password')}
          password
          autoComplete="new-password"
          value={password}
          onChangeText={setPassword}
          error={error?.message ?? null}
          traceId={error?.traceId ?? null}
        />
        <PasswordChecklist password={password} />
      </View>
      <Checkbox checked={consent} onToggle={() => setConsent(!consent)} error={consentError}>
        <AppText variant="bodySm" style={styles.consentText} color={theme.colors.onSurfaceVariant}>
          {t('auth.register.consent')}
        </AppText>
      </Checkbox>
      {consentError && (
        <AppText variant="bodySm" color={theme.colors.error}>
          {t('auth.register.consentRequired')}
        </AppText>
      )}
      <Button label={t('auth.register.cta')} onPress={submit} loading={submitting} />
      <View style={styles.footerRow}>
        <AppText variant="bodyMd" color={theme.colors.onSurfaceVariant}>
          {t('auth.register.haveAccount')}{' '}
        </AppText>
        <Link href="/(auth)/login" replace asChild>
          <AppText variant="bodyMd" color={theme.colors.primary}>
            {t('auth.register.loginLink')}
          </AppText>
        </Link>
      </View>
    </AuthScreen>
  );
}

const styles = StyleSheet.create({
  passwordBlock: { gap: 10 },
  consentText: { flex: 1 },
  footerRow: { flexDirection: 'row', justifyContent: 'center', marginTop: 8 },
});
