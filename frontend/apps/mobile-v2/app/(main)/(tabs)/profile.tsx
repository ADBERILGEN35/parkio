import { useState } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useQuery } from '@tanstack/react-query';
import { hasAdminRole, hasPrivilegedRole } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Avatar } from '@/components/ui/Avatar';
import { Card } from '@/components/ui/Card';
import { ConfirmModal } from '@/components/ui/ConfirmModal';
import { ListRow } from '@/components/ui/ListRow';
import { OptionSheet } from '@/components/ui/OptionSheet';
import { TrustRing, trustBandOf } from '@/components/ui/TrustRing';
import { appConfig } from '@/config/env';
import { myProfileQueryOptions, myStatsQueryOptions } from '@/data/query-options/me';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { formatDate } from '@/lib/time';
import { usersApi } from '@/services/api';
import { signOut } from '@/services/auth';
import { unregisterPushToken } from '@/services/pushNotifications';
import { useAuthStore } from '@/state/authStore';
import { useAppearance, type AppearancePreference } from '@/theme/ThemeProvider';
import { useTheme } from '@/theme/ThemeProvider';

/** Profile hub (brief §12.9): identity + trust/level/points + settings rail. */
export default function ProfileScreen() {
  const theme = useTheme();
  const t = useT();
  const { locale, setLocale } = useLocale();
  const appearance = useAppearance();
  const router = useRouter();
  const user = useAuthStore((s) => s.user);
  const [signOutAllConfirm, setSignOutAllConfirm] = useState(false);
  const [languageSheet, setLanguageSheet] = useState(false);
  const [appearanceSheet, setAppearanceSheet] = useState(false);
  const { colors } = theme;

  const profile = useQuery(myProfileQueryOptions());
  const stats = useQuery(myStatsQueryOptions());

  const roles = user?.roles ?? [];
  const staff = hasPrivilegedRole(roles);
  const admin = hasAdminRole(roles);
  const displayName = profile.data?.displayName?.trim() || user?.email || '·';

  const doSignOut = async (allDevices: boolean) => {
    await unregisterPushToken();
    await signOut({ allDevices });
    router.replace('/(onboarding)/welcome');
  };

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        {/* Identity hero */}
        <View style={styles.hero}>
          <Avatar name={displayName} size={64} />
          <View style={styles.heroLabels}>
            <AppText variant="headlineMd" numberOfLines={1}>
              {displayName}
            </AppText>
            <AppText variant="bodySm" color={colors.onSurfaceVariant} numberOfLines={1}>
              {user?.email}
            </AppText>
            {profile.data?.createdAt ? (
              <AppText variant="labelSm" color={colors.outline}>
                {t('profile.memberSince', { date: formatDate(profile.data.createdAt, locale) })}
              </AppText>
            ) : null}
          </View>
        </View>

        {/* Trust / level / points */}
        <View style={styles.statsRow}>
          <Card tone={1} shadow={false} padding={12} style={styles.statTile}>
            <TrustRing score={stats.data?.trustScore ?? 100} size={52} strokeWidth={4} />
            <AppText variant="labelSm" color={colors.onSurfaceVariant} numberOfLines={1}>
              {t(`trust.${trustBandOf(stats.data?.trustScore ?? 100)}`)}
            </AppText>
          </Card>
          <Card tone={1} shadow={false} padding={12} style={styles.statTile}>
            <AppText variant="headlineMd" tabular>
              {stats.data?.currentLevel ?? '—'}
            </AppText>
            <AppText variant="labelSm" color={colors.onSurfaceVariant}>
              {t('profile.stats.level')}
            </AppText>
          </Card>
          <Card tone={1} shadow={false} padding={12} style={styles.statTile}>
            <AppText variant="headlineMd" tabular>
              {stats.data?.totalPoints ?? '—'}
            </AppText>
            <AppText variant="labelSm" color={colors.onSurfaceVariant}>
              {t('profile.stats.points')}
            </AppText>
          </Card>
        </View>

        {/* Account */}
        <Card style={styles.menuCard}>
          <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
            {t('profile.section.account')}
          </AppText>
          <ListRow icon="account-outline" label={t('profile.menu.account')} onPress={() => router.push('/(main)/profile/edit')} />
          <ListRow icon="car-outline" label={t('profile.menu.vehicle')} onPress={() => router.push('/(main)/profile/vehicle')} />
          <ListRow icon="history" label={t('profile.menu.parkingHistory')} onPress={() => router.push('/(main)/profile/parking-history')} />
          <ListRow icon="bell-outline" label={t('profile.menu.notifications')} onPress={() => router.push('/(main)/profile/preferences')} />
          {appConfig.features.smartReturn && (
            <ListRow icon="home-clock-outline" label={t('profile.menu.smartReturn')} sublabel={t('smartReturn.beta')} onPress={() => router.push('/(main)/smart-return')} />
          )}
          <ListRow icon="lock-outline" label={t('profile.menu.changePassword')} onPress={() => router.push('/(main)/profile/change-password')} />
        </Card>

        {/* App */}
        <Card style={styles.menuCard}>
          <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
            {t('profile.section.app')}
          </AppText>
          <ListRow icon="radar" label={t('profile.menu.impact')} onPress={() => router.push('/(main)/impact')} />
          <ListRow icon="bell-badge-outline" label={t('notifications.title')} onPress={() => router.push('/(main)/notifications')} />
          <ListRow icon="flag-outline" label={t('profile.menu.reports')} onPress={() => router.push('/(main)/reports')} />
          <ListRow
            icon="translate"
            label={t('profile.menu.language')}
            sublabel={locale === 'tr' ? 'Türkçe' : 'English'}
            onPress={() => setLanguageSheet(true)}
          />
          <ListRow
            icon="theme-light-dark"
            label={t('profile.menu.appearance')}
            sublabel={t(`profile.appearance.${appearance.preference}`)}
            onPress={() => setAppearanceSheet(true)}
          />
          <ListRow icon="information-outline" label={t('profile.menu.about')} onPress={() => router.push('/(main)/profile/about')} />
        </Card>

        {/* Staff */}
        {staff && (
          <Card style={styles.menuCard}>
            <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant}>
              {t('profile.section.staff')}
            </AppText>
            <ListRow icon="shield-account-outline" label={t('profile.menu.moderationQueue')} onPress={() => router.push('/(main)/moderation')} />
            {admin && (
              <ListRow icon="chart-box-outline" label={t('profile.menu.analytics')} onPress={() => router.push('/(main)/moderation/analytics')} />
            )}
          </Card>
        )}

        {/* Sign out */}
        <Card style={styles.menuCard}>
          <ListRow icon="logout" label={t('profile.signOut')} onPress={() => void doSignOut(false)} showChevron={false} />
          <ListRow
            icon="cellphone-remove"
            label={t('profile.signOutAll')}
            onPress={() => setSignOutAllConfirm(true)}
            showChevron={false}
            danger
          />
        </Card>
      </ScrollView>

      <OptionSheet
        visible={languageSheet}
        onClose={() => setLanguageSheet(false)}
        title={t('profile.menu.language')}
        options={[
          { value: 'tr', label: 'Türkçe' },
          { value: 'en', label: 'English' },
        ]}
        selected={locale}
        onSelect={(value) => {
          setLocale(value);
          setLanguageSheet(false);
          // Mirror to backend prefs so emails/pushes localize too (best-effort).
          void usersApi.updateMyPreferences({ preferredLocale: value }).catch(() => undefined);
        }}
      />
      <OptionSheet
        visible={appearanceSheet}
        onClose={() => setAppearanceSheet(false)}
        title={t('profile.menu.appearance')}
        options={(['system', 'light', 'dark'] as AppearancePreference[]).map((value) => ({
          value,
          label: t(`profile.appearance.${value}`),
        }))}
        selected={appearance.preference}
        onSelect={(value) => {
          appearance.setPreference(value);
          setAppearanceSheet(false);
        }}
      />
      <ConfirmModal
        visible={signOutAllConfirm}
        title={t('profile.signOutAll')}
        body={t('profile.signOutAllConfirm')}
        confirmLabel={t('profile.signOutAll')}
        cancelLabel={t('common.cancel')}
        confirmVariant="destructive"
        onConfirm={() => {
          setSignOutAllConfirm(false);
          void doSignOut(true);
        }}
        onCancel={() => setSignOutAllConfirm(false)}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  scroll: { padding: 20, gap: 14, paddingBottom: 32 },
  hero: { flexDirection: 'row', alignItems: 'center', gap: 14, paddingTop: 8 },
  heroLabels: { flex: 1, gap: 2 },
  statsRow: { flexDirection: 'row', gap: 8 },
  statTile: { flex: 1, alignItems: 'center', gap: 6, justifyContent: 'center' },
  menuCard: { gap: 2 },
});
