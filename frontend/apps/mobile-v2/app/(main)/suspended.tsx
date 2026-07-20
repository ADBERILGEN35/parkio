import { StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { useT } from '@/i18n/LocaleProvider';
import { signOut } from '@/services/auth';
import { useTheme } from '@/theme/ThemeProvider';

/** Account-suspended wall (403 ACCOUNT_NOT_ACTIVE). Appeals go through email/web. */
export default function SuspendedScreen() {
  const theme = useTheme();
  const t = useT();
  const router = useRouter();
  const { colors } = theme;

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]}>
      <View style={styles.center}>
        <View style={[styles.iconBubble, { backgroundColor: colors.errorContainer }]}>
          <MaterialCommunityIcons name="account-lock-outline" size={34} color={colors.error} />
        </View>
        <AppText variant="headlineMd" align="center">
          {t('auth.suspended.title')}
        </AppText>
        <AppText variant="bodyMd" align="center" color={colors.onSurfaceVariant}>
          {t('auth.suspended.body')}
        </AppText>
      </View>
      <Button
        label={t('auth.suspended.signOut')}
        variant="tonal"
        onPress={async () => {
          await signOut();
          router.replace('/(onboarding)/welcome');
        }}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, padding: 24, justifyContent: 'space-between' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12 },
  iconBubble: {
    width: 72,
    height: 72,
    borderRadius: 36,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 6,
  },
});
