import { useCallback, useEffect, useMemo, useRef } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';
import BottomSheet, { BottomSheetFlatList } from '@gorhom/bottom-sheet';
import type { Destination, ParkingCandidate, RecommendationResponse } from '@parkio/types';
import type { UseQueryResult } from '@tanstack/react-query';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { useT } from '@/i18n/LocaleProvider';
import { radius as radiusTokens, shadows } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';
import { RecommendationCard } from './RecommendationCard';

export type RecommendationsSheetProps = {
  destination: Destination;
  recommendations: UseQueryResult<RecommendationResponse, Error>;
  selectedCandidateId: string | null;
  onSelectCandidate: (candidate: ParkingCandidate) => void;
  onChangeDestination: () => void;
  onClearDestination: () => void;
  /** Hide sheet peek while SpotSheet / MunicipalFacilitySheet is open. */
  suppressed?: boolean;
};

const PEEK = 220;

export function RecommendationsSheet({
  destination,
  recommendations,
  selectedCandidateId,
  onSelectCandidate,
  onChangeDestination,
  onClearDestination,
  suppressed = false,
}: RecommendationsSheetProps) {
  const t = useT();
  const theme = useTheme();
  const { colors } = theme;
  const sheetRef = useRef<BottomSheet>(null);
  const snapPoints = useMemo(() => [PEEK, '58%'], []);

  const candidates = recommendations.data?.candidates ?? [];
  const loading = recommendations.isFetching && !recommendations.data;
  const error = recommendations.isError;
  const empty = recommendations.isSuccess && candidates.length === 0;
  const partial = Boolean(recommendations.data?.partial);

  useEffect(() => {
    if (suppressed) {
      sheetRef.current?.close();
      return;
    }
    sheetRef.current?.snapToIndex(0);
  }, [destination.label, suppressed]);

  const handleChange = useCallback((_index: number) => {
    // Keep destination even if user pans sheet down — clear is explicit.
  }, []);

  if (suppressed) {
    return null;
  }

  return (
    <BottomSheet
      ref={sheetRef}
      snapPoints={snapPoints}
      index={0}
      enablePanDownToClose={false}
      onChange={handleChange}
      backgroundStyle={[
        { backgroundColor: colors.surface, borderRadius: radiusTokens.sheet },
        shadows.ambientDeep,
      ]}
      handleIndicatorStyle={{ backgroundColor: colors.outlineVariant, width: 36 }}
    >
      <View style={styles.header}>
        <AppText
          variant="titleMd"
          numberOfLines={2}
          accessibilityRole="header"
          testID="assistant-recommendations-heading"
        >
          {t('assistant.recommendationsHeader', { destination: destination.label })}
        </AppText>
        <AppText variant="bodySm" color={colors.onSurfaceVariant}>
          {loading
            ? t('common.loading')
            : t('assistant.resultCount', { count: candidates.length })}
        </AppText>
        <View style={styles.actions}>
          <Pressable
            onPress={onChangeDestination}
            accessibilityRole="button"
            accessibilityLabel={t('assistant.changeDestination')}
            style={styles.linkBtn}
          >
            <AppText variant="labelMd" color={colors.primary}>
              {t('assistant.changeDestination')}
            </AppText>
          </Pressable>
          <Pressable
            onPress={onClearDestination}
            accessibilityRole="button"
            accessibilityLabel={t('assistant.clearDestination')}
            style={styles.linkBtn}
          >
            <AppText variant="labelMd" color={colors.onSurfaceVariant}>
              {t('assistant.clearDestination')}
            </AppText>
          </Pressable>
        </View>
        {partial ? (
          <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.banner}>
            {t('assistant.partialInventory')}
          </AppText>
        ) : null}
      </View>

      {loading ? (
        <View style={styles.centerBlock}>
          <ActivityIndicator color={colors.primary} accessibilityLabel={t('common.loading')} />
        </View>
      ) : null}

      {error ? (
        <View style={styles.centerBlock}>
          <AppText variant="bodyMd" color={colors.onSurface}>
            {t('assistant.recommendError')}
          </AppText>
          <Button label={t('common.retry')} onPress={() => void recommendations.refetch()} />
        </View>
      ) : null}

      {empty ? (
        <View style={styles.centerBlock}>
          <AppText variant="bodyMd">{t('assistant.emptyTitle')}</AppText>
          <AppText variant="bodySm" color={colors.onSurfaceVariant}>
            {t('assistant.emptyDescription')}
          </AppText>
        </View>
      ) : null}

      {!loading && !error && candidates.length > 0 ? (
        <BottomSheetFlatList
          data={candidates}
          keyExtractor={(item) => item.id}
          contentContainerStyle={styles.list}
          renderItem={({ item, index }) => (
            <RecommendationCard
              candidate={item}
              rankIndex={index}
              selected={item.id === selectedCandidateId}
              onSelect={onSelectCandidate}
            />
          )}
        />
      ) : null}
    </BottomSheet>
  );
}

const styles = StyleSheet.create({
  header: { paddingHorizontal: 16, paddingBottom: 8, gap: 4 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 16, marginTop: 4 },
  linkBtn: { minHeight: 44, justifyContent: 'center' },
  banner: { marginTop: 4 },
  list: { paddingHorizontal: 16, paddingBottom: 28, gap: 10 },
  centerBlock: { paddingHorizontal: 16, paddingVertical: 20, gap: 12 },
});
