import { Ionicons } from '@expo/vector-icons';
import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import type { Profile, UserStats } from '@parkio/types';
import { Badge, Card, Screen, SkeletonCard, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { appConfig } from '@/config/env';
import { usersApi } from '@/services/api';
import { MIN_TOUCH_TARGET, useTheme } from '@/theme';

export default function HomeScreen() {
  const profileQuery = useQuery<Profile>({ queryKey: ['me', 'profile'], queryFn: usersApi.getMyProfile });
  const statsQuery = useQuery<UserStats>({ queryKey: ['me', 'stats'], queryFn: usersApi.getMyStats });

  const loading = profileQuery.isPending || statsQuery.isPending;
  const error = profileQuery.isError || statsQuery.isError;

  return (
    <Screen contentStyle={styles.content}>
      <View style={styles.header}>
        <AppText variant="caption" tone="muted">
          Welcome back
        </AppText>
        <AppText variant="title">{greetingName(profileQuery.data)}</AppText>
      </View>

      {loading ? (
        <View style={styles.section}>
          <SkeletonCard />
          <SkeletonCard />
        </View>
      ) : error ? (
        <StateView
          glyph="⚠️"
          title="Couldn’t load your dashboard"
          description="Check your connection and try again."
          actionLabel="Retry"
          onAction={() => {
            void profileQuery.refetch();
            void statsQuery.refetch();
          }}
        />
      ) : (
        <>
          <StatsRow stats={statsQuery.data} />
          <QuickActions />
        </>
      )}
    </Screen>
  );
}

function greetingName(profile: Profile | undefined): string {
  if (!profile) return 'Driver';
  return profile.displayName?.trim() || profile.email.split('@')[0];
}

function StatsRow({ stats }: { stats: UserStats | undefined }) {
  if (!stats) return null;
  return (
    <Card>
      <AppText variant="label" tone="muted">
        Your impact
      </AppText>
      <View style={styles.statsRow}>
        <Stat value={String(stats.totalPoints)} label="Points" />
        <Stat value={String(stats.currentLevel)} label="Level" />
        <Stat value={String(stats.trustScore)} label="Trust" />
      </View>
      <Badge label={humanize(stats.trustBand)} tone="success" />
    </Card>
  );
}

function Stat({ value, label }: { value: string; label: string }) {
  return (
    <View style={styles.stat}>
      <AppText variant="title">{value}</AppText>
      <AppText variant="caption" tone="muted">
        {label}
      </AppText>
    </View>
  );
}

function QuickActions() {
  const router = useRouter();
  const actions = [
    { icon: 'map-outline' as const, label: 'Find parking', href: '/(main)/map' as const },
    { icon: 'add-circle-outline' as const, label: 'Share a spot', href: '/(main)/upload' as const },
    ...(appConfig.features.smartReturn
      ? [{ icon: 'home-outline' as const, label: 'Smart Return', href: '/(main)/smart-return' as const }]
      : []),
  ];

  return (
    <View style={styles.section}>
      <AppText variant="heading">Quick actions</AppText>
      <View style={styles.grid}>
        {actions.map((action) => (
          <QuickAction key={action.label} icon={action.icon} label={action.label} onPress={() => router.push(action.href)} />
        ))}
      </View>
    </View>
  );
}

function QuickAction({
  icon,
  label,
  onPress,
}: {
  icon: keyof typeof Ionicons.glyphMap;
  label: string;
  onPress: () => void;
}) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={({ pressed }) => [
        styles.action,
        {
          backgroundColor: pressed ? theme.colors.surfaceMuted : theme.colors.surface,
          borderColor: theme.colors.border,
          borderRadius: theme.radius.xl,
        },
      ]}
    >
      <Ionicons name={icon} size={26} color={theme.colors.primary} />
      <AppText variant="callout">{label}</AppText>
    </Pressable>
  );
}

function humanize(value: string): string {
  return value
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

const styles = StyleSheet.create({
  content: { gap: 24 },
  header: { gap: 4 },
  section: { gap: 12 },
  statsRow: { flexDirection: 'row', justifyContent: 'space-between', marginVertical: 16 },
  stat: { alignItems: 'center', flex: 1, gap: 4 },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  action: {
    minHeight: MIN_TOUCH_TARGET * 1.8,
    flexGrow: 1,
    flexBasis: '45%',
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    padding: 16,
  },
});
