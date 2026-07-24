import { useCallback, useState } from 'react';
import { ActivityIndicator, FlatList, StyleSheet, View } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { ConfirmModal } from '@/components/ui/ConfirmModal';
import { EmptyState } from '@/components/ui/EmptyState';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { Skeleton } from '@/components/ui/Skeleton';
import { ParkingSessionHistoryRow } from '@/features/parking/ParkingSessionHistoryRow';
import { useDeleteParkingSessionActions } from '@/features/parking/useParkingSessionHistory';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * Canonical ParkingSession terminal history + deletion (S1-P0-11).
 * Nested under Profile — no new tab. ACTIVE sessions stay on the Map banner.
 */
export default function ParkingHistoryScreen() {
  const theme = useTheme();
  const t = useT();
  const insets = useSafeAreaInsets();
  const { colors } = theme;
  const history = useDeleteParkingSessionActions();
  const [deleteTargetId, setDeleteTargetId] = useState<string | null>(null);
  const [deleteAllOpen, setDeleteAllOpen] = useState(false);

  const onDeletePress = useCallback((sessionId: string) => {
    if (history.rowDeleteDisabled) {
      return;
    }
    setDeleteTargetId(sessionId);
  }, [history.rowDeleteDisabled]);

  const confirmDeleteRow = useCallback(() => {
    const id = deleteTargetId;
    if (!id) {
      return;
    }
    setDeleteTargetId(null);
    void history.deleteSession(id);
  }, [deleteTargetId, history]);

  const confirmDeleteAll = useCallback(() => {
    setDeleteAllOpen(false);
    void history.deleteAllHistory();
  }, [history]);

  const { query, items } = history;
  const showInitialLoading = query.isPending && items.length === 0;
  const showError = query.isError && items.length === 0;

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader title={t('parkingSession.history.title')} />

      {items.length > 0 ? (
        <View style={styles.deleteAllRow}>
          <Button
            label={t('parkingSession.history.deleteAll.cta')}
            size="sm"
            variant="ghost"
            disabled={history.deleteAllDisabled}
            onPress={() => setDeleteAllOpen(true)}
            accessibilityHint={t('parkingSession.history.deleteAll.a11y')}
          />
        </View>
      ) : null}

      {showInitialLoading ? (
        <View style={styles.loading} accessibilityRole="progressbar" accessibilityLabel={t('parkingSession.history.loading')}>
          <Skeleton height={64} radius={14} />
          <Skeleton height={64} radius={14} />
          <Skeleton height={64} radius={14} />
        </View>
      ) : showError ? (
        <EmptyState
          title={t('parkingSession.history.error')}
          body={t('parkingSession.history.errorBody')}
          ctaLabel={t('common.retry')}
          onCtaPress={() => void history.refetch()}
        />
      ) : items.length === 0 ? (
        <EmptyState
          title={t('parkingSession.history.emptyTitle')}
          body={t('parkingSession.history.emptyBody')}
        />
      ) : (
        <FlatList
          data={items}
          keyExtractor={(item) => item.id}
          contentContainerStyle={[styles.list, { paddingBottom: insets.bottom + 28 }]}
          refreshing={query.isRefetching && !query.isFetchingNextPage}
          onRefresh={() => void history.refetch()}
          onEndReached={() => history.fetchNextPage()}
          onEndReachedThreshold={0.4}
          ListFooterComponent={
            history.isFetchingNextPage ? (
              <View style={styles.footer} accessibilityRole="progressbar">
                <ActivityIndicator color={colors.primary} />
              </View>
            ) : query.isFetchNextPageError ? (
              <View style={styles.footer}>
                <Button
                  label={t('common.retry')}
                  size="sm"
                  variant="tonal"
                  onPress={() => history.fetchNextPage()}
                />
              </View>
            ) : null
          }
          renderItem={({ item }) => (
            <ParkingSessionHistoryRow
              session={item}
              deleteDisabled={history.rowDeleteDisabled}
              deleting={history.pendingRowId === item.id}
              onDeletePress={onDeletePress}
            />
          )}
          ItemSeparatorComponent={() => <View style={styles.sep} />}
          testID="parking-history-list"
        />
      )}

      {history.phase === 'deletingAll' || history.phase === 'deletingRow' ? (
        <View style={styles.busyBanner} accessibilityLiveRegion="polite">
          <AppText variant="labelSm" color={colors.onSurfaceVariant}>
            {history.phase === 'deletingAll'
              ? t('parkingSession.history.deleteAll.busy')
              : t('parkingSession.history.delete.busy')}
          </AppText>
        </View>
      ) : null}

      <ConfirmModal
        visible={deleteTargetId !== null}
        title={t('parkingSession.history.delete.confirmTitle')}
        body={t('parkingSession.history.delete.confirmBody')}
        confirmLabel={t('parkingSession.history.delete.confirmCta')}
        cancelLabel={t('common.cancel')}
        confirmVariant="destructive"
        loading={history.phase === 'deletingRow'}
        onConfirm={confirmDeleteRow}
        onCancel={() => {
          if (history.phase !== 'deletingRow') {
            setDeleteTargetId(null);
          }
        }}
      />

      <ConfirmModal
        visible={deleteAllOpen}
        title={t('parkingSession.history.deleteAll.confirmTitle')}
        body={t('parkingSession.history.deleteAll.confirmBody')}
        confirmLabel={t('parkingSession.history.deleteAll.confirmCta')}
        cancelLabel={t('common.cancel')}
        confirmVariant="destructive"
        loading={history.phase === 'deletingAll'}
        onConfirm={confirmDeleteAll}
        onCancel={() => {
          if (history.phase !== 'deletingAll') {
            setDeleteAllOpen(false);
          }
        }}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  deleteAllRow: { paddingHorizontal: 12, alignItems: 'flex-end' },
  loading: { padding: 20, gap: 10 },
  list: { paddingHorizontal: 16, paddingTop: 8, gap: 0 },
  sep: { height: 8 },
  footer: { paddingVertical: 16, alignItems: 'center' },
  busyBanner: { paddingHorizontal: 20, paddingBottom: 8 },
});
