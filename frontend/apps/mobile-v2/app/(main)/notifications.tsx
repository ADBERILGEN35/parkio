import { useMemo, useState } from 'react';
import { FlatList, StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { isUnreadNotification, type AppNotification, type NotificationType } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { useNowTick } from '@/components/spots/FreshnessRing';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { EmptyState } from '@/components/ui/EmptyState';
import { PressableScale } from '@/components/ui/PressableScale';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { useLocale, useT } from '@/i18n/LocaleProvider';
import { formatRelative } from '@/lib/time';
import { notificationsApi } from '@/services/api';
import { useTheme } from '@/theme/ThemeProvider';

type Filter = 'all' | 'unread' | 'moderation' | 'gamification';

const FILTER_TYPES: Record<Exclude<Filter, 'all' | 'unread'>, NotificationType[]> = {
  moderation: ['WARNING', 'SYSTEM'],
  gamification: ['LEVEL_UP', 'POINT_EARNED'],
};

const TYPE_ICON: Record<NotificationType, React.ComponentProps<typeof MaterialCommunityIcons>['name']> = {
  NEARBY_PARKING: 'map-marker-radius-outline',
  LEVEL_UP: 'chevron-double-up',
  POINT_EARNED: 'star-four-points-outline',
  WARNING: 'alert-outline',
  SYSTEM: 'information-outline',
  SMART_RETURN_PROMPT: 'home-clock-outline',
  SMART_RETURN_AVAILABLE: 'home-map-marker',
};

/** In-app deep link per type/metadata (backend also sends `metadata.deeplink`). */
function routeFor(notification: AppNotification): string {
  const deeplink = notification.metadata?.deeplink;
  if (deeplink === '/reports') return '/(main)/reports';
  switch (notification.type) {
    case 'LEVEL_UP':
    case 'POINT_EARNED':
      return '/(main)/impact';
    case 'SMART_RETURN_PROMPT':
      return '/(main)/smart-return';
    case 'SMART_RETURN_AVAILABLE':
    case 'NEARBY_PARKING':
      return '/(main)/(tabs)/map';
    case 'WARNING':
    case 'SYSTEM':
      return '/(main)/reports';
  }
}

export default function NotificationsScreen() {
  const theme = useTheme();
  const t = useT();
  const { locale } = useLocale();
  const router = useRouter();
  const queryClient = useQueryClient();
  const insets = useSafeAreaInsets();
  const [filter, setFilter] = useState<Filter>('all');
  const now = useNowTick(60_000);
  const { colors } = theme;

  const query = useQuery({
    queryKey: ['notifications'],
    queryFn: () => notificationsApi.getMyNotifications(),
    refetchInterval: 60_000,
  });

  const markRead = useMutation({
    mutationFn: (id: string) => notificationsApi.markRead(id),
    onSuccess: (updated) => {
      queryClient.setQueryData<AppNotification[]>(['notifications'], (current) =>
        current?.map((item) => (item.id === updated.id ? updated : item)),
      );
    },
  });

  const notifications = useMemo(() => query.data ?? [], [query.data]);
  const unread = useMemo(() => notifications.filter(isUnreadNotification), [notifications]);

  const filtered = useMemo(() => {
    switch (filter) {
      case 'all':
        return notifications;
      case 'unread':
        return unread;
      default:
        return notifications.filter((item) => FILTER_TYPES[filter].includes(item.type));
    }
  }, [notifications, unread, filter]);

  const markAllRead = () => {
    for (const item of unread) {
      markRead.mutate(item.id);
    }
  };

  const open = (notification: AppNotification) => {
    if (isUnreadNotification(notification)) {
      markRead.mutate(notification.id);
    }
    router.push(routeFor(notification));
  };

  const filters: { key: Filter; label: string }[] = [
    { key: 'all', label: t('notifications.filter.all') },
    { key: 'unread', label: t('notifications.filter.unread') },
    { key: 'moderation', label: t('notifications.filter.moderation') },
    { key: 'gamification', label: t('notifications.filter.gamification') },
  ];

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader title={t('notifications.title')} />
      <View style={styles.filters}>
        {filters.map((item) => (
          <Chip
            key={item.key}
            label={item.label}
            size="sm"
            selected={filter === item.key}
            onPress={() => setFilter(item.key)}
          />
        ))}
      </View>
      {unread.length > 0 && (
        <View style={styles.markAll}>
          <Button
            label={t('notifications.markAllRead')}
            variant="ghost"
            size="sm"
            block={false}
            onPress={markAllRead}
          />
        </View>
      )}

      {query.isLoading ? (
        <View style={styles.loading}>
          <Skeleton height={72} radius={14} />
          <Skeleton height={72} radius={14} />
          <Skeleton height={72} radius={14} />
        </View>
      ) : filtered.length === 0 ? (
        <EmptyState title={filter === 'unread' ? t('notifications.emptyUnread') : t('notifications.empty')} />
      ) : (
        <FlatList
          data={filtered}
          keyExtractor={(item) => item.id}
          contentContainerStyle={[styles.list, { paddingBottom: insets.bottom + 28 }]}
          refreshing={query.isRefetching}
          onRefresh={() => void query.refetch()}
          renderItem={({ item }) => {
            const isUnread = isUnreadNotification(item);
            return (
              <PressableScale
                scaleTo={0.98}
                onPress={() => open(item)}
                accessibilityRole="button"
                accessibilityLabel={item.title}
              >
                <View
                  style={[
                    styles.card,
                    { backgroundColor: colors.surface },
                    theme.mode === 'light' ? styles.cardShadow : null,
                  ]}
                >
                  {isUnread && <View style={[styles.accent, { backgroundColor: colors.primary }]} />}
                  <View style={[styles.iconBubble, { backgroundColor: colors.surfaceContainer1 }]}>
                    <MaterialCommunityIcons name={TYPE_ICON[item.type]} size={18} color={colors.primary} />
                  </View>
                  <View style={styles.cardLabels}>
                    <AppText
                      variant="bodyMd"
                      numberOfLines={1}
                      style={isUnread ? styles.unreadTitle : undefined}
                    >
                      {item.title}
                    </AppText>
                    <AppText variant="bodySm" color={colors.onSurfaceVariant} numberOfLines={2}>
                      {item.body}
                    </AppText>
                    <AppText variant="labelSm" color={colors.outline}>
                      {formatRelative(item.createdAt, now, locale)}
                    </AppText>
                  </View>
                  <MaterialCommunityIcons name="chevron-right" size={18} color={colors.outline} />
                </View>
              </PressableScale>
            );
          }}
        />
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  filters: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, paddingHorizontal: 20, paddingBottom: 6 },
  markAll: { flexDirection: 'row', justifyContent: 'flex-end', paddingHorizontal: 14 },
  loading: { padding: 20, gap: 10 },
  list: { padding: 20, paddingTop: 8, gap: 10 },
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    padding: 12,
    borderRadius: 14,
    overflow: 'hidden',
  },
  cardShadow: {
    shadowColor: '#000000',
    shadowOpacity: 0.04,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 3 },
    elevation: 1,
  },
  accent: { position: 'absolute', left: 0, top: 0, bottom: 0, width: 3 },
  iconBubble: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cardLabels: { flex: 1, gap: 2 },
  unreadTitle: { fontFamily: 'Inter_600SemiBold' },
});
