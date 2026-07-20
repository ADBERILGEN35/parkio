import { useEffect, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useMutation } from '@tanstack/react-query';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { TextField } from '@/components/ui/TextField';
import { AuthScreen } from '@/features/auth/AuthScreen';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { authApi } from '@/services/api';
import { useTheme } from '@/theme/ThemeProvider';
import { useToast } from '@/providers/ToastProvider';

/**
 * Email verification — reached from the emailed deep link
 * (`parkio-v2://verify-email?token=…`) or manually with a pasted code.
 * Screen phase derives entirely from the mutation state.
 */
export default function VerifyEmailScreen() {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const router = useRouter();
  const toast = useToast();
  const params = useLocalSearchParams<{ token?: string; email?: string }>();
  const linkToken = typeof params.token === 'string' ? params.token : '';
  const email = typeof params.email === 'string' ? params.email : '';
  const [token, setToken] = useState(linkToken);

  const verifyMutation = useMutation({
    mutationFn: (value: string) => authApi.verifyEmail({ token: value.trim() }),
  });

  // The emailed link auto-verifies once on arrival.
  useEffect(() => {
    if (linkToken) {
      verifyMutation.mutate(linkToken);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linkToken]);

  const error = verifyMutation.isError ? describeApiError(verifyMutation.error, t) : null;
  const verifying = verifyMutation.isPending;

  const resend = async () => {
    if (!email) {
      router.replace('/(auth)/login');
      return;
    }
    try {
      await authApi.resendVerification({ email, locale });
      toast.show(t('auth.checkEmail.resent'), 'success');
    } catch (raw) {
      toast.show(describeApiError(raw, t).message, 'error');
    }
  };

  if (verifyMutation.isSuccess) {
    return (
      <AuthScreen title={t('auth.verify.successTitle')} showBack={false}>
        <View style={styles.center}>
          <View style={[styles.iconBubble, { backgroundColor: theme.colors.secondaryContainer }]}>
            <MaterialCommunityIcons name="check" size={34} color={theme.colors.secondary} />
          </View>
          <AppText variant="bodyLg" align="center" color={theme.colors.onSurfaceVariant}>
            {t('auth.verify.successBody')}
          </AppText>
        </View>
        <Button label={t('onboarding.welcome.signIn')} onPress={() => router.replace('/(auth)/login')} />
      </AuthScreen>
    );
  }

  return (
    <AuthScreen title={t('auth.verify.tokenLabel')}>
      {verifying ? (
        <View style={styles.center}>
          <ActivityIndicator color={theme.colors.primary} />
          <AppText variant="bodyMd" color={theme.colors.onSurfaceVariant}>
            {t('auth.verify.verifying')}
          </AppText>
        </View>
      ) : (
        <>
          <TextField
            label={t('auth.verify.tokenLabel')}
            placeholder={t('auth.verify.tokenPlaceholder')}
            autoCapitalize="none"
            autoCorrect={false}
            value={token}
            onChangeText={setToken}
            error={error?.message ?? null}
            traceId={error?.traceId ?? null}
          />
          <Button
            label={t('auth.verify.submit')}
            onPress={() => verifyMutation.mutate(token)}
            disabled={token.trim().length === 0}
          />
          {verifyMutation.isError && (
            <Button label={t('auth.login.notVerifiedCta')} variant="ghost" onPress={() => void resend()} />
          )}
        </>
      )}
    </AuthScreen>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: 'center', gap: 12, paddingVertical: 24 },
  iconBubble: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
