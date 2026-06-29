import { useQuery } from '@tanstack/react-query';
import { FlatList, RefreshControl, StyleSheet, View } from 'react-native';
import { isUnreadNotification, type AppNotification } from '@parkio/types';
import { Badge, Card, Screen, SkeletonCard, StateView } from '@/components/ui';
import { AppText } from '@/components/ui/AppText';
import { notificationsApi } from '@/services/api';
import { useTheme } from '@/theme';

export default function NotificationsScreen() {
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
          glyph="⚠️"
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
          glyph="🔔"
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
        ItemSeparatorComponent={() => <View style={styles.separator} />}
        renderItem={({ item }) => <NotificationRow notification={item} />}
        refreshControl={
          <RefreshControl refreshing={query.isFetching} onRefresh={() => void query.refetch()} />
        }
      />
    </Screen>
  );
}

function NotificationRow({ notification }: { notification: AppNotification }) {
  const theme = useTheme();
  const unread = isUnreadNotification(notification);
  return (
    <Card style={{ borderColor: unread ? theme.colors.primary : theme.colors.border }}>
      <View style={styles.rowHeader}>
        <AppText variant="subtitle" style={styles.flex}>
          {notification.title}
        </AppText>
        {unread ? <Badge label="New" tone="primary" /> : null}
      </View>
      <AppText variant="body" tone="muted">
        {notification.body}
      </AppText>
    </Card>
  );
}

const styles = StyleSheet.create({
  content: { gap: 16 },
  list: { gap: 12 },
  listContent: { padding: 16, gap: 12 },
  heading: { marginBottom: 4 },
  separator: { height: 12 },
  rowHeader: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 4 },
  flex: { flex: 1 },
});
