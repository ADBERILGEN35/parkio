import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Alert, StyleSheet, View } from 'react-native';
import type { Profile } from '@parkio/types';
import { Button, Card, LinkRow, Screen, Skeleton, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { appConfig } from '@/config/env';
import { useAuth } from '@/hooks/useAuth';
import { usersApi } from '@/services/api';
import { useToast } from '@/providers/ToastProvider';
import { toUserMessage } from '@/utils/errors';

export default function ProfileScreen() {
  const { user, roles, logout, logoutAll } = useAuth();
  const router = useRouter();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const isStaff = roles.includes('MODERATOR') || roles.includes('ADMIN');

  const profileQuery = useQuery<Profile>({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });

  const confirmLogoutAll = () => {
    Alert.alert(
      'Log out of all devices?',
      'This signs you out everywhere and revokes all active sessions.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Log out all', style: 'destructive', onPress: () => void runLogout(logoutAll) },
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
      <AppText variant="title">Profile</AppText>

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
          title="Couldn’t load your profile"
          actionLabel="Retry"
          onAction={() => void profileQuery.refetch()}
        />
      ) : (
        <Card>
          <AppText variant="subtitle">{profileQuery.data.displayName ?? 'Parkio driver'}</AppText>
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
        <AppText variant="heading">Account</AppText>
        <LinkRow
          icon="person-outline"
          title="Edit profile"
          description="Display name, phone, and city"
          testID="profile.edit"
          onPress={() => router.push('/(main)/profile-edit')}
        />
        <LinkRow
          icon="car-outline"
          title="Vehicle"
          description="Vehicle type and plate"
          testID="profile.vehicle"
          onPress={() => router.push('/(main)/vehicle')}
        />
        <LinkRow
          icon="options-outline"
          title="Preferences"
          description="Search radius and notifications"
          testID="profile.preferences"
          onPress={() => router.push('/(main)/preferences')}
        />
        <LinkRow
          icon="key-outline"
          title="Change password"
          testID="profile.changePassword"
          onPress={() => router.push('/(main)/change-password')}
        />
      </View>

      <View style={styles.section}>
        <AppText variant="heading">Activity</AppText>
        <LinkRow
          icon="map-outline"
          title="My spots"
          description="Spots you shared"
          testID="profile.mySpots"
          onPress={() => router.push('/(main)/my-spots')}
        />
        <LinkRow
          icon="trophy-outline"
          title="Leaderboard"
          description="Top contributors"
          testID="profile.leaderboard"
          onPress={() => router.push('/(main)/leaderboard')}
        />
        <LinkRow
          icon="star-outline"
          title="Your Impact"
          description="Points, levels, and activity history"
          testID="profile.impact"
          onPress={() => router.push('/(main)/impact')}
        />
        <LinkRow
          icon="flag-outline"
          title="My reports"
          description="Reports and appeals"
          testID="profile.reports"
          onPress={() => router.push('/(main)/reports')}
        />
      </View>

      {appConfig.features.smartReturn ? (
        <View style={styles.section}>
          <AppText variant="heading">Parking</AppText>
          <LinkRow
            icon="home-outline"
            title="Smart Return"
            description="One parking check near home, right before you head back."
            testID="profile.smartReturn"
            onPress={() => router.push('/(main)/smart-return')}
          />
        </View>
      ) : null}

      {isStaff ? (
        <View style={styles.section}>
          <AppText variant="heading">Staff</AppText>
          <LinkRow
            icon="shield-checkmark-outline"
            title="Moderation"
            description="Review cases and appeals"
            testID="profile.moderation"
            onPress={() => router.push('/(main)/moderation')}
          />
          <LinkRow
            icon="stats-chart-outline"
            title="Analytics"
            description="Platform KPIs and metrics"
            testID="profile.analytics"
            onPress={() => router.push('/(main)/analytics')}
          />
        </View>
      ) : null}

      <View style={styles.section}>
        <Button
          label="Log out"
          testID="profile.logout"
          variant="secondary"
          onPress={() => void runLogout(logout)}
          loading={busy}
        />
        <Button
          label="Log out of all devices"
          testID="profile.logoutAll"
          variant="ghost"
          onPress={confirmLogoutAll}
          disabled={busy}
        />
      </View>

      <AppText variant="caption" tone="muted">
        Signed in as {user?.email ?? 'unknown'}
      </AppText>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: 24 },
  section: { gap: 12 },
  skeletonRows: { gap: 10 },
});