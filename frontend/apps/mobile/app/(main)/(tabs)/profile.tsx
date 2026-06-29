import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Alert, StyleSheet, View } from 'react-native';
import type { Profile } from '@parkio/types';
import { Button, Card, Screen, Skeleton, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { useAuth } from '@/hooks/useAuth';
import { usersApi } from '@/services/api';
import { useToast } from '@/providers/ToastProvider';
import { toUserMessage } from '@/utils/errors';

export default function ProfileScreen() {
  const { user, logout, logoutAll } = useAuth();
  const toast = useToast();
  const [busy, setBusy] = useState(false);

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
      // The (main) guard redirects to /login once the session clears.
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
          <Skeleton width="50%" height={20} />
          <View style={{ height: 10 }} />
          <Skeleton width="70%" height={14} />
        </Card>
      ) : profileQuery.isError ? (
        <StateView
          glyph="⚠️"
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
        <Button label="Log out" variant="secondary" onPress={() => void runLogout(logout)} loading={busy} />
        <Button label="Log out of all devices" variant="ghost" onPress={confirmLogoutAll} disabled={busy} />
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
});
