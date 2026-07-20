import { Image, StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { PressableScale } from '@/components/ui/PressableScale';
import { PulseMotif } from '@/components/ui/PulseMotif';
import { useLocale } from '@/i18n/LocaleProvider';
import type { ParkioLocale } from '@/i18n/translations';
import { radius, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

/** First-run language choice — TR preselected (product default). */
export default function LanguageScreen() {
  const theme = useTheme();
  const { locale, setLocale } = useLocale();
  const router = useRouter();
  const { colors } = theme;

  const choose = (next: ParkioLocale) => {
    setLocale(next);
    router.push('/(onboarding)/slides');
  };

  const options: { value: ParkioLocale; label: string; hint: string }[] = [
    { value: 'tr', label: 'Türkçe', hint: 'Türkiye' },
    { value: 'en', label: 'English', hint: 'International' },
  ];

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
      <View style={styles.hero}>
        <PulseMotif size={260} core={false} style={styles.pulse} />
        <Image
          source={require('../../assets/images/icon.png')}
          style={styles.logo}
          accessibilityLabel="Parkio"
        />
        <AppText variant="headlineLg" align="center">
          Parkio
        </AppText>
        <AppText variant="bodyMd" align="center" color={colors.onSurfaceVariant}>
          Dilini seç · Choose your language
        </AppText>
      </View>

      <View style={styles.options}>
        {options.map((option) => {
          const selected = option.value === locale;
          return (
            <PressableScale
              key={option.value}
              scaleTo={0.97}
              onPress={() => choose(option.value)}
              accessibilityRole="button"
              accessibilityLabel={option.label}
              accessibilityState={{ selected }}
              style={[
                styles.option,
                {
                  backgroundColor: colors.surface,
                  borderColor: selected ? colors.primary : colors.outlineVariant,
                  borderWidth: selected ? 2 : 1,
                },
                theme.mode === 'light' ? shadows.ambientSoft : null,
              ]}
            >
              <View style={styles.optionLabels}>
                <AppText variant="titleMd">{option.label}</AppText>
                <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                  {option.hint}
                </AppText>
              </View>
              <MaterialCommunityIcons
                name={selected ? 'radiobox-marked' : 'radiobox-blank'}
                size={22}
                color={selected ? colors.primary : colors.outline}
              />
            </PressableScale>
          );
        })}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, padding: 24, justifyContent: 'space-between' },
  hero: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 8 },
  pulse: { position: 'absolute' },
  logo: { width: 84, height: 84, borderRadius: 22, marginBottom: 10 },
  options: { gap: 12, paddingBottom: 24 },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 18,
    paddingVertical: 16,
    borderRadius: radius.card,
    gap: 12,
  },
  optionLabels: { flex: 1, gap: 2 },
});
