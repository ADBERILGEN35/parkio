import { useState } from 'react';
import { Keyboard, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { GeocodeResult } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Glass } from '@/components/ui/Glass';
import { IconButton } from '@/components/ui/IconButton';
import { Skeleton } from '@/components/ui/Skeleton';
import { useT } from '@/i18n/LocaleProvider';
import { fonts } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';
import { usePlaceSearch, useRecentSearches } from './hooks';

export interface MapSearchOverlayProps {
  /** Radius/level chip content, e.g. "1200 m · Sv 3". */
  radiusChip: string | null;
  onRadiusChipPress: () => void;
  showSearchArea: boolean;
  onSearchArea: () => void;
  onLocate: () => void;
  onPickPlace: (place: GeocodeResult) => void;
}

/**
 * Floating glass search pill + typeahead panel (pen `Pa3rs` + map chips row).
 * Collapsed: pill + radius chip + "Bu bölgede ara". Focused: expanding panel
 * with results / recent searches.
 */
export function MapSearchOverlay({
  radiusChip,
  onRadiusChipPress,
  showSearchArea,
  onSearchArea,
  onLocate,
  onPickPlace,
}: MapSearchOverlayProps) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const [query, setQuery] = useState('');
  const [focused, setFocused] = useState(false);
  const places = usePlaceSearch(query);
  const { recent, push } = useRecentSearches();

  const pick = (place: GeocodeResult) => {
    push(place);
    setQuery('');
    setFocused(false);
    Keyboard.dismiss();
    onPickPlace(place);
  };

  const showPanel = focused;
  const showingRecent = query.trim().length < 3;
  const results = showingRecent ? recent : (places.data ?? []);

  return (
    <View style={styles.host} pointerEvents="box-none">
      <Glass radius={999} style={styles.pill} contentStyle={styles.pillContent}>
        <MaterialCommunityIcons name="magnify" size={20} color={colors.onSurfaceVariant} />
        <TextInput
          value={query}
          onChangeText={setQuery}
          onFocus={() => setFocused(true)}
          placeholder={t('map.searchPlaceholder')}
          placeholderTextColor={colors.outline}
          style={[styles.input, { color: colors.onSurface, fontFamily: fonts.regular }]}
          returnKeyType="search"
          accessibilityLabel={t('map.searchPlaceholder')}
        />
        {focused ? (
          <IconButton
            icon="close"
            size={32}
            variant="glassless"
            accessibilityLabel={t('common.close')}
            onPress={() => {
              setQuery('');
              setFocused(false);
              Keyboard.dismiss();
            }}
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

      {!showPanel && (
        <View style={styles.chipsRow} pointerEvents="box-none">
          {radiusChip ? (
            <Pressable onPress={onRadiusChipPress} accessibilityRole="button" accessibilityLabel={radiusChip}>
              <Glass radius={999} contentStyle={styles.chipContent}>
                <MaterialCommunityIcons name="radar" size={14} color={colors.primary} />
                <AppText variant="labelSm" tabular color={colors.onSurface}>
                  {radiusChip}
                </AppText>
              </Glass>
            </Pressable>
          ) : null}
          {showSearchArea ? (
            <Pressable onPress={onSearchArea} accessibilityRole="button" accessibilityLabel={t('map.searchThisArea')}>
              <Glass radius={999} contentStyle={styles.chipContent}>
                <MaterialCommunityIcons name="refresh" size={14} color={colors.primary} />
                <AppText variant="labelSm" color={theme.mode === 'dark' ? colors.primaryFixedDim : colors.primary}>
                  {t('map.searchThisArea')}
                </AppText>
              </Glass>
            </Pressable>
          ) : null}
        </View>
      )}

      {showPanel && (
        <Glass radius={20} style={styles.panel} contentStyle={styles.panelContent}>
          {showingRecent && results.length > 0 ? (
            <AppText variant="labelMd" uppercase color={colors.onSurfaceVariant} style={styles.panelHeading}>
              {t('map.recentSearches')}
            </AppText>
          ) : null}
          {places.isFetching && !showingRecent ? (
            <View style={styles.loadingBlock}>
              <Skeleton height={18} width="80%" />
              <Skeleton height={18} width="60%" />
            </View>
          ) : results.length === 0 ? (
            <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.panelHeading}>
              {showingRecent ? t('map.searchPlaceholder') : t('map.noPlaceResults')}
            </AppText>
          ) : (
            <ScrollView keyboardShouldPersistTaps="handled" style={styles.results}>
              {results.map((place) => (
                <Pressable
                  key={place.id}
                  onPress={() => pick(place)}
                  accessibilityRole="button"
                  accessibilityLabel={place.primary}
                  style={({ pressed }) => [styles.resultRow, pressed && { opacity: 0.6 }]}
                >
                  <MaterialCommunityIcons
                    name={showingRecent ? 'history' : 'map-marker-outline'}
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
          )}
        </Glass>
      )}
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
  chipsRow: { flexDirection: 'row', gap: 8 },
  chipContent: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingHorizontal: 11,
    paddingVertical: 7,
  },
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
