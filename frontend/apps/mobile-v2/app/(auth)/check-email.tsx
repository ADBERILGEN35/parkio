import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { PulseMotif } from '@/components/ui/PulseMotif';
import { AuthScreen } from '@/features/auth/AuthScreen';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { authApi } from '@/services/api';
import { useTheme } from '@/theme/ThemeProvider';
import { useToast } from '@/providers/ToastProvider';

const RESEND_COOLDOWN_S = 60;

export default function CheckEmailScreen() {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const router = useRouter();
  const toast = useToast();
  const params = useLocalSearchParams<{ email?: string }>();
  const email = typeof params.email === 'string' ? params.email : '';
  const [cooldown, setCooldown] = useState(RESEND_COOLDOWN_S);
  const [sending, setSending] = useState(false);

  useEffect(() => {
    if (cooldown <= 0) {
      return;
    }
    const id = setInterval(() => setCooldown((value) => value - 1), 1000);
    return () => clearInterval(id);
  }, [cooldown]);

  const resend = async () => {
    setSending(true);
    try {
      await authApi.resendVerification({ email, locale });
      toast.show(t('auth.checkEmail.resent'), 'success');
      setCooldown(RESEND_COOLDOWN_S);
    } catch (raw) {
      toast.show(describeApiError(raw, t).message, 'error');
    } finally {
      setSending(false);
    }
  };

  return (
    <AuthScreen title={t('auth.checkEmail.title')}>
      <View style={styles.hero}>
        <PulseMotif size={170} style={styles.pulse} />
        <View style={[styles.iconBubble, { backgroundColor: theme.colors.primaryFixed }]}>
          <MaterialCommunityIcons name="email-outline" size={30} color={theme.colors.primary} />
        </View>
      </View>
      <AppText variant="bodyLg" align="center" color={theme.colors.onSurfaceVariant}>
        {t('auth.checkEmail.body', { email })}
      </AppText>
      <Button
        label={
          cooldown > 0
            ? t('auth.checkEmail.resendIn', { seconds: cooldown })
            : t('auth.checkEmail.resend')
        }
        variant="tonal"
        onPress={resend}
        disabled={cooldown > 0}
        loading={sending}
      />
      <Button
        label={t('auth.verify.submit')}
        onPress={() => router.push({ pathname: '/(auth)/verify-email', params: { email } })}
      />
      <Button
        label={t('auth.checkEmail.wrongEmail')}
        variant="ghost"
        onPress={() => router.replace('/(auth)/register')}
      />
    </AuthScreen>
  );
}

const styles = StyleSheet.create({
  hero: { alignItems: 'center', justifyContent: 'center', height: 180 },
  pulse: { position: 'absolute' },
  iconBubble: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
