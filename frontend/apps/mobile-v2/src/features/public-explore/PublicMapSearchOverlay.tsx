import { useEffect, useState } from 'react';
import { Keyboard, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { GeocodeResult } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Glass } from '@/components/ui/Glass';
import { IconButton } from '@/components/ui/IconButton';
import { Skeleton } from '@/components/ui/Skeleton';
import { isPublicSearchRateLimited } from '@/features/public-explore/isPublicSearchRateLimited';
import { usePublicPlaceSearch } from '@/features/public-explore/usePublicPlaceSearch';
import { useT } from '@/i18n/LocaleProvider';
import { fonts } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

export interface PublicMapSearchOverlayProps {
  selectedDestination: GeocodeResult | null;
  onPickPlace: (place: GeocodeResult) => void;
  onClearDestination: () => void;
  onLocate: () => void;
  /** True while the autocomplete panel is open (suppress discovery chrome). */
  onSearchActiveChange?: (active: boolean) => void;
}

/**
 * Public Explore search — same glass pill / panel language as {@link MapSearchOverlay},
 * but public geocoding only (no recents / favourites / private geocoding).
 */
export function PublicMapSearchOverlay({
  selectedDestination,
  onPickPlace,
  onClearDestination,
  onLocate,
  onSearchActiveChange,
}: PublicMapSearchOverlayProps) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const [query, setQuery] = useState('');
  const [focused, setFocused] = useState(false);
  const places = usePublicPlaceSearch(query);

  useEffect(() => {
    onSearchActiveChange?.(focused);
  }, [focused, onSearchActiveChange]);

  const pick = (place: GeocodeResult) => {
    setQuery('');
    setFocused(false);
    Keyboard.dismiss();
    onPickPlace(place);
  };

  const clearOrClose = () => {
    if (query.length > 0) {
      setQuery('');
      return;
    }
    if (selectedDestination) {
      onClearDestination();
    }
    setFocused(false);
    Keyboard.dismiss();
  };

  const showPanel = focused;
  const trimmedLive = query.trim();
  const waitingForMinLength = trimmedLive.length > 0 && trimmedLive.length < 3;
  const searching = trimmedLive.length >= 3;
  const rateLimited = searching && isPublicSearchRateLimited(places.error);
  const transportError = searching && places.isError && !rateLimited;
  const results = searching ? (places.data ?? []) : [];

  const inputValue =
    focused || query.length > 0
      ? query
      : selectedDestination?.primary ?? '';

  return (
    <View style={styles.host} pointerEvents="box-none">
      <Glass radius={999} style={styles.pill} contentStyle={styles.pillContent}>
        <MaterialCommunityIcons name="magnify" size={20} color={colors.onSurfaceVariant} />
        <TextInput
          value={inputValue}
          onChangeText={(text) => {
            setQuery(text);
            if (!focused) setFocused(true);
          }}
          onFocus={() => {
            setFocused(true);
            if (selectedDestination && query.length === 0) {
              setQuery(selectedDestination.primary);
            }
          }}
          placeholder={t('publicExplore.search.placeholder')}
          placeholderTextColor={colors.outline}
          style={[styles.input, { color: colors.onSurface, fontFamily: fonts.regular }]}
          returnKeyType="search"
          accessibilityLabel={t('publicExplore.search.placeholder')}
        />
        {focused || selectedDestination || query.length > 0 ? (
          <IconButton
            icon="close"
            size={32}
            variant="glassless"
            accessibilityLabel={
              selectedDestination && query.length === 0 && !focused
                ? t('publicExplore.search.clearDestination')
                : t('common.close')
            }
            onPress={clearOrClose}
          />
        ) : (
          <IconButton
            icon="crosshairs-gps"
            size={32}
            variant="glassless"
            accessibilityLabel={t('share.location.useMyLocation')}
            onPress={onLocate}
          />
        )}
      </Glass>

      {showPanel ? (
        <Glass radius={20} style={styles.panel} contentStyle={styles.panelContent}>
          {waitingForMinLength ? (
            <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.panelHeading}>
              {t('publicExplore.search.minLengthHint')}
            </AppText>
          ) : null}

          {searching && places.isFetching ? (
            <View style={styles.loadingBlock} accessibilityLiveRegion="polite">
              <Skeleton height={18} width="80%" />
              <Skeleton height={18} width="60%" />
            </View>
          ) : null}

          {rateLimited ? (
            <AppText
              variant="bodySm"
              color={colors.onSurface}
              style={styles.panelHeading}
              accessibilityLiveRegion="assertive"
            >
              {t('publicExplore.search.rateLimited')}
            </AppText>
          ) : null}

          {transportError ? (
            <AppText
              variant="bodySm"
              color={colors.onSurface}
              style={styles.panelHeading}
              accessibilityLiveRegion="assertive"
            >
              {t('publicExplore.search.error')}
            </AppText>
          ) : null}

          {searching && !places.isFetching && !places.isError && results.length === 0 ? (
            <AppText
              variant="bodySm"
              color={colors.onSurfaceVariant}
              style={styles.panelHeading}
              accessibilityLiveRegion="polite"
            >
              {t('publicExplore.search.noResults')}
            </AppText>
          ) : null}

          {results.length > 0 ? (
            <ScrollView keyboardShouldPersistTaps="handled" style={styles.results}>
              {results.map((place) => (
                <Pressable
                  key={place.id}
                  onPress={() => pick(place)}
                  accessibilityRole="button"
                  accessibilityLabel={[place.primary, place.secondary].filter(Boolean).join(', ')}
                  style={({ pressed }) => [styles.resultRow, pressed && { opacity: 0.6 }]}
                >
                  <MaterialCommunityIcons
                    name="map-marker-outline"
                    size={18}
                    color={colors.onSurfaceVariant}
                  />
                  <View style={styles.resultLabels}>
                    <AppText variant="bodyMd" numberOfLines={1}>
                      {place.primary}
                    </AppText>
                    {place.secondary ? (
                      <AppText variant="bodySm" color={colors.onSurfaceVariant} numberOfLines={1}>
                        {place.secondary}
                      </AppText>
                    ) : null}
                  </View>
                </Pressable>
              ))}
            </ScrollView>
          ) : null}

          {!searching && !waitingForMinLength ? (
            <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.panelHeading}>
              {t('publicExplore.search.placeholder')}
            </AppText>
          ) : null}
        </Glass>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  host: { gap: 8 },
  pill: {},
  pillContent: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingLeft: 14,
    paddingRight: 6,
    height: 48,
    gap: 8,
  },
  input: { flex: 1, fontSize: 15, paddingVertical: 8 },
  panel: { maxHeight: 320 },
  panelContent: { padding: 12 },
  panelHeading: { paddingHorizontal: 4, paddingBottom: 6 },
  loadingBlock: { gap: 10, padding: 8 },
  results: {},
  resultRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingVertical: 9,
    paddingHorizontal: 4,
  },
  resultLabels: { flex: 1, gap: 1 },
});
