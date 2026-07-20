import { ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UserPreference } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Card } from '@/components/ui/Card';
import { Chip } from '@/components/ui/Chip';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { Toggle } from '@/components/ui/Toggle';
import { useAccessPolicy } from '@/features/map/hooks';
import { useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { usersApi } from '@/services/api';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme/ThemeProvider';

const RADIUS_OPTIONS = [300, 600, 1200, 1800, 2500];

function formatRadius(meters: number): string {
  return meters >= 1000 ? `${(meters / 1000).toFixed(1).replace(/\.0$/, '')} km` : `${meters} m`;
}

export default function PreferencesScreen() {
  const theme = useTheme();
  const t = useT();
  const toast = useToast();
  const queryClient = useQueryClient();
  const insets = useSafeAreaInsets();
  const { colors } = theme;

  const prefs = useQuery({ queryKey: ['my-prefs'], queryFn: () => usersApi.getMyPreferences() });
  const policy = useAccessPolicy();

  const update = useMutation({
    mutationFn: (body: Parameters<typeof usersApi.updateMyPreferences>[0]) =>
      usersApi.updateMyPreferences(body),
    onSuccess: (updated: UserPreference) => {
      queryClient.setQueryData(['my-prefs'], updated);
      toast.show(t('profile.prefs.saved'), 'success');
    },
    onError: (error) => toast.show(describeApiError(error, t).message, 'error'),
  });

  const maxRadius = policy.data?.searchRadiusMeters ?? 2500;

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader title={t('profile.prefs.title')} />
      {prefs.isLoading || !prefs.data ? (
        <View style={styles.loading}>
          <Skeleton height={72} radius={16} />
          <Skeleton height={110} radius={16} />
        </View>
      ) : (
        <ScrollView contentContainerStyle={[styles.scroll, { paddingBottom: insets.bottom + 24 }]}>
          <Card style={styles.card}>
            <View style={styles.rowBetween}>
              <View style={styles.rowLabels}>
                <AppText variant="titleMd">{t('profile.prefs.enabled')}</AppText>
                <AppText variant="bodySm" color={colors.onSurfaceVariant}>
                  {t('profile.prefs.enabledHint')}
                </AppText>
              </View>
              <Toggle
                value={prefs.data.notificationsEnabled}
                onValueChange={(value) => update.mutate({ notificationsEnabled: value })}
                accessibilityLabel={t('profile.prefs.enabled')}
              />
            </View>
          </Card>

          <Card style={styles.card}>
            <AppText variant="titleMd">{t('profile.prefs.radius')}</AppText>
            <AppText variant="bodySm" color={colors.onSurfaceVariant}>
              {t('profile.prefs.radiusHint', { max: formatRadius(maxRadius) })}
            </AppText>
            <View style={styles.chips}>
              {RADIUS_OPTIONS.map((radius) => (
                <Chip
                  key={radius}
                  label={formatRadius(radius)}
                  selected={prefs.data.preferredRadiusMeters === radius}
                  disabled={radius > maxRadius}
                  onPress={() => update.mutate({ preferredRadiusMeters: radius })}
                />
              ))}
            </View>
          </Card>
        </ScrollView>
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  loading: { padding: 20, gap: 12 },
  scroll: { padding: 20, paddingTop: 8, gap: 12 },
  card: { gap: 10 },
  rowBetween: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  rowLabels: { flex: 1, gap: 3 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
});
