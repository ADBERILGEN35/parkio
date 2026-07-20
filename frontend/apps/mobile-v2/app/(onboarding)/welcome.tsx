import { useEffect } from 'react';
import { Image, StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { PulseMotif } from '@/components/ui/PulseMotif';
import { useT } from '@/i18n/LocaleProvider';
import { useOnboardingStore } from '@/state/onboardingStore';
import { useTheme } from '@/theme/ThemeProvider';

/** Auth landing (brief §12.1.8) — email-only entry, honest beta framing. */
export default function WelcomeScreen() {
  const theme = useTheme();
  const t = useT();
  const router = useRouter();
  const { colors } = theme;

  // Reaching this screen completes first-run onboarding; cold starts land here.
  useEffect(() => {
    useOnboardingStore.getState().markCompleted();
  }, []);

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
      <View style={styles.hero}>
        <PulseMotif size={300} rings={4} core={false} style={styles.pulse} />
        <Image
          source={require('../../assets/images/icon.png')}
          style={styles.logo}
          accessibilityLabel="Parkio"
        />
        <AppText variant="displayLg" align="center">
          Parkio
        </AppText>
        <AppText variant="bodyLg" align="center" color={colors.onSurfaceVariant} style={styles.tagline}>
          {t('onboarding.welcome.tagline')}
        </AppText>
        <View style={[styles.betaChip, { backgroundColor: colors.surfaceContainer2 }]}>
          <AppText variant="labelSm" color={colors.onSurfaceVariant}>
            {t('profile.about.stage')}
          </AppText>
        </View>
      </View>
      <View style={styles.actions}>
        <Button label={t('onboarding.welcome.signIn')} onPress={() => router.push('/(auth)/login')} />
        <Button
          label={t('onboarding.welcome.register')}
          variant="tonal"
          onPress={() => router.push('/(auth)/register')}
        />
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, padding: 24, justifyContent: 'space-between' },
  hero: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 10 },
  pulse: { position: 'absolute' },
  logo: { width: 96, height: 96, borderRadius: 24, marginBottom: 12 },
  tagline: { maxWidth: 300 },
  betaChip: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 999,
    marginTop: 6,
  },
  actions: { gap: 10, paddingBottom: 16 },
});
