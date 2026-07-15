import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Alert, StyleSheet, View } from 'react-native';
import type { Profile } from '@parkio/types';
import { hasAdminRole, hasPrivilegedRole } from '@parkio/types';
import { Button, Card, LinkRow, Screen, Skeleton, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { appConfig } from '@/config/env';
import { useAuth } from '@/hooks/useAuth';
import { usersApi } from '@/services/api';
import { useToast } from '@/providers/ToastProvider';
import { toUserMessage } from '@/utils/errors';
import { useLocale } from '@/i18n/LocaleProvider';

export default function ProfileScreen() {
  const { user, roles, logout, logoutAll } = useAuth();
  const router = useRouter();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const { t } = useLocale();
  const showModeration = hasPrivilegedRole(roles);
  const showAnalytics = hasAdminRole(roles);

  const profileQuery = useQuery<Profile>({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });

  const confirmLogoutAll = () => {
    Alert.alert(
      t('Log out of all devices?'),
      t('This signs you out everywhere and revokes all active sessions.'),
      [
        { text: t('Cancel'), style: 'cancel' },
        { text: t('Log out all'), style: 'destructive', onPress: () => void runLogout(logoutAll) },
      ],
    );
  };

  const runLogout = async (action: () => Promise<void>) => {
    setBusy(true);
    try {
      await action();
    } catch (error) {
      toast.showError(toUserMessage(error));
      setBusy(false);
    }
  };

  return (
    <Screen contentStyle={styles.content}>
      <AppText variant="title">{t('Profile')}</AppText>

      {profileQuery.isPending ? (
        <Card>
          <View style={styles.skeletonRows}>
            <Skeleton width="50%" height={20} />
            <Skeleton width="70%" height={14} />
          </View>
        </Card>
      ) : profileQuery.isError ? (
        <StateView
          icon="alert-circle-outline"
          title={t('Couldn’t load your profile')}
          actionLabel={t('Retry')}
          onAction={() => void profileQuery.refetch()}
        />
      ) : (
        <Card>
          <AppText variant="subtitle">{profileQuery.data.displayName ?? t('Parkio driver')}</AppText>
          <AppText variant="body" tone="muted">
            {profileQuery.data.email}
          </AppText>
          {profileQuery.data.city ? (
            <AppText variant="callout" tone="muted">
              {profileQuery.data.city}
            </AppText>
          ) : null}
        </Card>
      )}

      <View style={styles.section}>
        <AppText variant="heading">{t('Account')}</AppText>
        <LinkRow
          icon="person-outline"
          title={t('Edit profile')}
          description={t('Display name, phone, and city')}
          testID="profile.edit"
          onPress={() => router.push('/(main)/profile-edit')}
        />
        <LinkRow
          icon="car-outline"
          title={t('Vehicle')}
          description={t('Vehicle type and plate')}
          testID="profile.vehicle"
          onPress={() => router.push('/(main)/vehicle')}
        />
        <LinkRow
          icon="options-outline"
          title={t('Preferences')}
          description={t('Search radius and notifications')}
          testID="profile.preferences"
          onPress={() => router.push('/(main)/preferences')}
        />
        <LinkRow
          icon="key-outline"
          title={t('Change password')}
          testID="profile.changePassword"
          onPress={() => router.push('/(main)/change-password')}
        />
      </View>

      <View style={styles.section}>
        <AppText variant="heading">{t('Activity')}</AppText>
        <LinkRow
          icon="map-outline"
          title={t('My spots')}
          description={t('Spots you shared')}
          testID="profile.mySpots"
          onPress={() => router.push('/(main)/my-spots')}
        />
        <LinkRow
          icon="trophy-outline"
          title={t('Leaderboard')}
          description={t('Top contributors')}
          testID="profile.leaderboard"
          onPress={() => router.push('/(main)/leaderboard')}
        />
        <LinkRow
          icon="star-outline"
          title={t('Your Impact')}
          description={t('Points, levels, and activity history')}
          testID="profile.impact"
          onPress={() => router.push('/(main)/impact')}
        />
        <LinkRow
          icon="flag-outline"
          title={t('My reports')}
          description={t('Reports and appeals')}
          testID="profile.reports"
          onPress={() => router.push('/(main)/reports')}
        />
      </View>

      {appConfig.features.smartReturn ? (
        <View style={styles.section}>
          <AppText variant="heading">{t('Parking')}</AppText>
          <LinkRow
            icon="home-outline"
            title={t('Smart Return')}
            description={t('One parking check near home, right before you head back.')}
            testID="profile.smartReturn"
            onPress={() => router.push('/(main)/smart-return')}
          />
        </View>
      ) : null}

      {showModeration || showAnalytics ? (
        <View style={styles.section}>
          <AppText variant="heading">{t('Staff')}</AppText>
          {showModeration ? (
            <LinkRow
              icon="shield-checkmark-outline"
              title={t('Moderation')}
              description={t('Review cases and appeals')}
              testID="profile.moderation"
              onPress={() => router.push('/(main)/moderation')}
            />
          ) : null}
          {showAnalytics ? (
            <LinkRow
              icon="stats-chart-outline"
              title={t('Analytics')}
              description={t('Platform KPIs and metrics')}
              testID="profile.analytics"
              onPress={() => router.push('/(main)/analytics')}
            />
          ) : null}
        </View>
      ) : null}

      <View style={styles.section}>
        <AppText variant="heading">{t('More')}</AppText>
        <LinkRow
          icon="notifications-outline"
          title={t('Notifications')}
          testID="profile.notifications"
          onPress={() => router.push('/(main)/(tabs)/notifications')}
        />
        <LinkRow
          icon="information-circle-outline"
          title={t('About the app')}
          description={t('App version and build details for support.')}
          testID="profile.about"
          onPress={() => router.push('/(main)/about')}
        />
      </View>

      <View style={styles.section}>
        <Button
          label={t('Log out')}
          testID="profile.logout"
          variant="secondary"
          onPress={() => void runLogout(logout)}
          loading={busy}
        />
        <Button
          label={t('Log out of all devices')}
          testID="profile.logoutAll"
          variant="ghost"
          onPress={confirmLogoutAll}
          disabled={busy}
        />
      </View>

      <AppText variant="caption" tone="muted" testID="profile.signedInAs">
        {t('Signed in as')} {user?.email ?? '—'}
      </AppText>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: 24 },
  section: { gap: 12 },
  skeletonRows: { gap: 10 },
});
