import { Ionicons } from '@expo/vector-icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { memo } from 'react';
import { FlatList, Pressable, RefreshControl, StyleSheet, View } from 'react-native';
import { formatRelativeTime } from '@parkio/geo';
import {
  isUnreadNotification,
  type AppNotification,
  type NotificationType,
} from '@parkio/types';
import { Badge, Button, Screen, SkeletonCard, StateView, type BadgeTone } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { notificationsApi } from '@/services/api';
import { useTheme } from '@/theme';

/** Per-type icon + badge tone — mirrors the web NOTIFICATION_TYPE_VISUALS. */
const TYPE_VISUALS: Record<NotificationType, { icon: keyof typeof Ionicons.glyphMap; tone: BadgeTone }> = {
  NEARBY_PARKING: { icon: 'car-outline', tone: 'primary' },
  LEVEL_UP: { icon: 'medal-outline', tone: 'success' },
  POINT_EARNED: { icon: 'star-outline', tone: 'success' },
  WARNING: { icon: 'warning-outline', tone: 'warning' },
  SYSTEM: { icon: 'information-circle-outline', tone: 'neutral' },
  SMART_RETURN_PROMPT: { icon: 'car-sport-outline', tone: 'primary' },
  SMART_RETURN_AVAILABLE: { icon: 'home-outline', tone: 'success' },
};

function typeVisual(type: NotificationType) {
  return TYPE_VISUALS[type] ?? { icon: 'notifications-outline' as const, tone: 'neutral' as BadgeTone };
}

function humanizeType(value: string): string {
  return value
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

export default function NotificationsScreen() {
  const theme = useTheme();
  const query = useQuery<AppNotification[]>({
    queryKey: ['notifications'],
    queryFn: notificationsApi.getMyNotifications,
  });

  if (query.isPending) {
    return (
      <Screen contentStyle={styles.content}>
        <AppText variant="title">Notifications</AppText>
        <View style={styles.list}>
          <SkeletonCard />
          <SkeletonCard />
          <SkeletonCard />
        </View>
      </Screen>
    );
  }

  if (query.isError) {
    return (
      <Screen scroll={false}>
        <StateView
          icon="cloud-offline-outline"
          title="Couldn’t load notifications"
          description="Check your connection and try again."
          actionLabel="Retry"
          onAction={() => void query.refetch()}
        />
      </Screen>
    );
  }

  const notifications = query.data;

  if (notifications.length === 0) {
    return (
      <Screen scroll={false}>
        <StateView
          icon="notifications-outline"
          title="No notifications yet"
          description="Activity on your spots and points will show up here."
        />
      </Screen>
    );
  }

  return (
    <Screen scroll={false} padded={false}>
      <FlatList
        data={notifications}
        keyExtractor={(item) => item.id}
        contentContainerStyle={styles.listContent}
        ListHeaderComponent={
          <AppText variant="title" style={styles.heading}>
            Notifications
          </AppText>
        }
        renderItem={({ item }) => <NotificationRow notification={item} />}
        refreshControl={
          <RefreshControl
            refreshing={query.isFetching}
            onRefresh={() => void query.refetch()}
            colors={[theme.colors.primary]}
            tintColor={theme.colors.primary}
          />
        }
      />
    </Screen>
  );
}

/**
 * Web NotificationItemCard, translated: unread rows are white cards with a
 * 4px primary left accent and soft shadow; read rows sit on a faint tonal
 * surface. Leading icon disc, type soft-badge, and relative timestamp match
 * the web layout. Memoized — rows are static once fetched, so pull-to-refresh
 * re-renders only rows whose data actually changed.
 */
const NotificationRow = memo(function NotificationRow({ notification }: { notification: AppNotification }) {
  const theme = useTheme();
  const queryClient = useQueryClient();
  const unread = isUnreadNotification(notification);
  const visual = typeVisual(notification.type);

  // Mirrors the web MarkReadButton: PATCH /notifications/{id}/read (idempotent),
  // then patch the row in the cached list so the unread state clears in place.
  const markRead = useMutation({
    mutationFn: () => notificationsApi.markRead(notification.id),
    onSuccess: (updated) => {
      queryClient.setQueryData<AppNotification[]>(['notifications'], (current) =>
        current?.map((item) => (item.id === updated.id ? updated : item)),
      );
    },
  });

  return (
    <View
      style={[
        styles.row,
        { borderRadius: theme.radius.lg },
        unread
          ? {
              backgroundColor: theme.colors.surface,
              borderLeftWidth: 4,
              borderLeftColor: theme.colors.primary,
              ...theme.elevation.card,
            }
          : {
              backgroundColor:
                theme.scheme === 'dark' ? theme.colors.surfaceMuted : 'rgba(239, 244, 255, 0.6)',
            },
      ]}
    >
      <View
        style={[
          styles.iconDisc,
          {
            backgroundColor: unread ? theme.colors.primarySoft : theme.colors.skeleton,
            borderRadius: theme.radius.full,
          },
        ]}
      >
        <Ionicons
          name={visual.icon}
          size={18}
          color={unread ? theme.colors.primary : theme.colors.textMuted}
        />
      </View>

      <View style={styles.body}>
        <View style={styles.titleRow}>
          <AppText
            variant="body"
            tone={unread ? 'default' : 'muted'}
            style={[styles.flex, unread ? styles.titleUnread : null]}
            numberOfLines={1}
          >
            {notification.title}
          </AppText>
          {unread ? (
            <View style={[styles.unreadDot, { backgroundColor: theme.colors.primary }]} accessibilityLabel="Unread" />
          ) : null}
        </View>

        <AppText variant="body" tone="muted">
          {notification.body}
        </AppText>

        <View style={styles.metaRow}>
          <Badge label={humanizeType(notification.type)} tone={visual.tone} />
          <AppText variant="caption" tone="muted">
            {formatRelativeTime(notification.createdAt)}
          </AppText>
          {unread ? (
            <Pressable
              testID={`notifications.markRead.${notification.id}`}
              accessibilityRole="button"
              accessibilityLabel="Mark as read"
              disabled={markRead.isPending}
              onPress={() => markRead.mutate()}
              style={styles.markReadButton}
            >
              <AppText variant="caption" style={{ color: theme.colors.primary, fontWeight: '600' }}>
                {markRead.isPending ? 'Marking…' : 'Mark as read'}
              </AppText>
            </Pressable>
          ) : null}
        </View>

        {markRead.isError ? (
          <AppText variant="caption" style={{ color: theme.colors.danger }}>
            Could not mark as read. Try again.
          </AppText>
        ) : null}

        <NotificationCta type={notification.type} />
      </View>
    </View>
  );
});

/**
 * Type-driven CTAs matching web notification interactions. Backend
 * `metadata.deeplink` values are web paths, so the stable notification type
 * drives native routing.
 */
function NotificationCta({ type }: { type: NotificationType }) {
  const theme = useTheme();
  const router = useRouter();

  if (type === 'SMART_RETURN_PROMPT') {
    return (
      <View style={styles.ctaRow}>
        <Button
          label="Set today's return"
          testID="notifications.smartReturn.prompt"
          leading={<Ionicons name="car" size={16} color={theme.colors.onPrimary} />}
          onPress={() => router.push('/(main)/smart-return')}
        />
      </View>
    );
  }
  if (type === 'SMART_RETURN_AVAILABLE') {
    return (
      <View style={styles.ctaRow}>
        <Button
          label="Open map"
          testID="notifications.smartReturn.available"
          leading={<Ionicons name="map-outline" size={16} color={theme.colors.onPrimary} />}
          onPress={() => router.push({ pathname: '/(main)/map', params: { smartReturn: '1' } })}
        />
      </View>
    );
  }
  if (type === 'POINT_EARNED' || type === 'LEVEL_UP') {
    return (
      <View style={styles.ctaRow}>
        <Button
          label="View impact"
          testID="notifications.impact"
          variant="secondary"
          onPress={() => router.push('/(main)/impact')}
        />
      </View>
    );
  }
  if (type === 'WARNING') {
    return (
      <View style={styles.ctaRow}>
        <Button
          label="View reports"
          testID="notifications.reports"
          variant="secondary"
          onPress={() => router.push('/(main)/reports')}
        />
      </View>
    );
  }
  return null;
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  list: { gap: 12 },
  listContent: { paddingHorizontal: 20, paddingVertical: 16, gap: 12 },
  heading: { marginBottom: 4 },
  row: { flexDirection: 'row', gap: 10, padding: 12 },
  iconDisc: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center', marginTop: 2 },
  body: { flex: 1, gap: 4 },
  titleRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  titleUnread: { fontWeight: '600' },
  unreadDot: { width: 8, height: 8, borderRadius: 999 },
  metaRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 2, flexWrap: 'wrap' },
  markReadButton: { marginLeft: 'auto', paddingVertical: 2, paddingHorizontal: 4 },
  ctaRow: { marginTop: 8 },
  flex: { flex: 1 },
});
