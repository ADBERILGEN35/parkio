import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { isStrongPassword } from '@parkio/validation';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { TextField } from '@/components/ui/TextField';
import { AuthScreen } from '@/features/auth/AuthScreen';
import { PasswordChecklist } from '@/features/auth/PasswordChecklist';
import { useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { authApi } from '@/services/api';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * New-password entry — reached from the emailed deep link
 * (`parkio-v2://reset-password?token=…`) or with a pasted code.
 */
export default function ResetPasswordScreen() {
  const theme = useTheme();
  const t = useT();
  const router = useRouter();
  const params = useLocalSearchParams<{ token?: string }>();
  const [token, setToken] = useState(typeof params.token === 'string' ? params.token : '');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [success, setSuccess] = useState(false);
  const [error, setError] = useState<{ message: string; traceId: string | null } | null>(null);
  const [confirmError, setConfirmError] = useState<string | null>(null);

  const submit = async () => {
    setError(null);
    setConfirmError(null);
    if (!isStrongPassword(password)) {
      setError({ message: t('common.error.generic'), traceId: null });
      return;
    }
    if (password !== confirm) {
      setConfirmError(t('auth.reset.mismatch'));
      return;
    }
    setSubmitting(true);
    try {
      await authApi.resetPassword({ token: token.trim(), newPassword: password });
      setSuccess(true);
    } catch (raw) {
      setError(describeApiError(raw, t));
    } finally {
      setSubmitting(false);
    }
  };

  if (success) {
    return (
      <AuthScreen title={t('auth.reset.successTitle')} showBack={false}>
        <View style={styles.center}>
          <View style={[styles.iconBubble, { backgroundColor: theme.colors.secondaryContainer }]}>
            <MaterialCommunityIcons name="check" size={34} color={theme.colors.secondary} />
          </View>
          <AppText variant="bodyLg" align="center" color={theme.colors.onSurfaceVariant}>
            {t('auth.reset.successBody')}
          </AppText>
        </View>
        <Button label={t('onboarding.welcome.signIn')} onPress={() => router.replace('/(auth)/login')} />
      </AuthScreen>
    );
  }

  return (
    <AuthScreen title={t('auth.reset.title')}>
      <TextField
        label={t('auth.reset.tokenLabel')}
        placeholder={t('auth.reset.tokenPlaceholder')}
        autoCapitalize="none"
        autoCorrect={false}
        value={token}
        onChangeText={setToken}
      />
      <View style={styles.passwordBlock}>
        <TextField
          label={t('auth.reset.newPassword')}
          password
          autoComplete="new-password"
          value={password}
          onChangeText={setPassword}
        />
        <PasswordChecklist password={password} />
      </View>
      <TextField
        label={t('auth.reset.confirmPassword')}
        password
        value={confirm}
        onChangeText={setConfirm}
        error={confirmError ?? error?.message ?? null}
        traceId={error?.traceId ?? null}
      />
      <Button
        label={t('auth.reset.cta')}
        onPress={submit}
        loading={submitting}
        disabled={token.trim().length === 0}
      />
    </AuthScreen>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: 'center', gap: 12, paddingVertical: 24 },
  passwordBlock: { gap: 10 },
  iconBubble: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
