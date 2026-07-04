import { Ionicons } from '@expo/vector-icons';
import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Alert, Pressable, StyleSheet, View } from 'react-native';
import type { Profile } from '@parkio/types';
import { Button, Card, Screen, Skeleton, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { appConfig } from '@/config/env';
import { useAuth } from '@/hooks/useAuth';
import { usersApi } from '@/services/api';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme';
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

      {appConfig.features.smartReturn ? (
        <View style={styles.section}>
          <AppText variant="heading">Parking</AppText>
          <SmartReturnRow />
        </View>
      ) : null}

      <View style={styles.section}>
        <AppText variant="heading">Account</AppText>
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

/** Entry point to the Smart Return screen — the web profile's Smart Return section. */
function SmartReturnRow() {
  const theme = useTheme();
  const router = useRouter();
  return (
    <Card padded={false}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Smart Return"
        testID="profile.smartReturn"
        onPress={() => router.push('/(main)/smart-return')}
        style={({ pressed }) => [
          styles.linkRow,
          { backgroundColor: pressed ? theme.colors.surfaceMuted : 'transparent', borderRadius: theme.radius.xl },
        ]}
      >
        <View style={[styles.linkDisc, { backgroundColor: theme.colors.primarySoft, borderRadius: theme.radius.full }]}>
          <Ionicons name="home" size={18} color={theme.colors.primary} />
        </View>
        <View style={styles.linkText}>
          <AppText variant="subtitle">Smart Return</AppText>
          <AppText variant="caption" tone="muted">
            One parking check near home, right before you head back.
          </AppText>
        </View>
        <Ionicons name="chevron-forward" size={18} color={theme.colors.textMuted} />
      </Pressable>
    </Card>
  );
}

const styles = StyleSheet.create({
  content: { gap: 24 },
  section: { gap: 12 },
  skeletonRows: { gap: 10 },
  linkRow: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 14, minHeight: 44 },
  linkDisc: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
  linkText: { flex: 1, gap: 2 },
});
