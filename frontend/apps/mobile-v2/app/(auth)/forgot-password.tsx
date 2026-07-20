import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { forgotPasswordSchema } from '@parkio/validation';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { TextField } from '@/components/ui/TextField';
import { AuthScreen } from '@/features/auth/AuthScreen';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { authApi } from '@/services/api';
import { useTheme } from '@/theme/ThemeProvider';

export default function ForgotPasswordScreen() {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<{ message: string; traceId: string | null } | null>(null);

  const submit = async () => {
    const parsed = forgotPasswordSchema.safeParse({ email: email.trim() });
    if (!parsed.success) {
      setError({ message: t('common.requiredField'), traceId: null });
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await authApi.forgotPassword({ ...parsed.data, locale });
      setSent(true);
    } catch (raw) {
      setError(describeApiError(raw, t));
    } finally {
      setSubmitting(false);
    }
  };

  if (sent) {
    return (
      <AuthScreen title={t('auth.forgot.sentTitle')}>
        <View style={styles.center}>
          <View style={[styles.iconBubble, { backgroundColor: theme.colors.primaryFixed }]}>
            <MaterialCommunityIcons name="email-check-outline" size={30} color={theme.colors.primary} />
          </View>
          <AppText variant="bodyLg" align="center" color={theme.colors.onSurfaceVariant}>
            {t('auth.forgot.sentBody')}
          </AppText>
        </View>
        <Button
          label={t('auth.reset.title')}
          onPress={() => router.push('/(auth)/reset-password')}
        />
        <Button
          label={t('onboarding.welcome.signIn')}
          variant="ghost"
          onPress={() => router.replace('/(auth)/login')}
        />
      </AuthScreen>
    );
  }

  return (
    <AuthScreen title={t('auth.forgot.title')} subtitle={t('auth.forgot.body')}>
      <TextField
        label={t('auth.email')}
        placeholder={t('auth.emailPlaceholder')}
        autoCapitalize="none"
        autoComplete="email"
        keyboardType="email-address"
        value={email}
        onChangeText={setEmail}
        error={error?.message ?? null}
        traceId={error?.traceId ?? null}
        onSubmitEditing={submit}
        returnKeyType="send"
      />
      <Button label={t('auth.forgot.cta')} onPress={submit} loading={submitting} />
    </AuthScreen>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: 'center', gap: 12, paddingVertical: 12 },
  iconBubble: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
