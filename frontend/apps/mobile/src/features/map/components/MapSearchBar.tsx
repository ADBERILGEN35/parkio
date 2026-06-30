import { Ionicons } from '@expo/vector-icons';
import { memo, useState } from 'react';
import {
  ActivityIndicator,
  Keyboard,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import type { GeocodeResult } from '@parkio/types';
import { AppText } from '@/components/ui';
import { HIT_SLOP, useTheme } from '@/theme';
import { usePlaceSearch } from '../hooks/usePlaceSearch';
import { useRecentSearches, type RecentSearch } from '../hooks/useRecentSearches';

export interface MapSearchBarProps {
  topOffset: number;
  onSelectPlace: (place: { primary: string; lat: number; lng: number }) => void;
}

/**
 * Place search with debounced autocomplete (shared geocoding API) and locally
 * stored recent searches. Selecting a result commits it to recents and hands the
 * coordinates to the parent (which moves the camera + runs a nearby search).
 */
function MapSearchBarImpl({ topOffset, onSelectPlace }: MapSearchBarProps) {
  const theme = useTheme();
  const [query, setQuery] = useState('');
  const [focused, setFocused] = useState(false);
  const { results, isSearching, isError, isActive } = usePlaceSearch(query);
  const { recents, add, clear } = useRecentSearches();

  const showPanel = focused && (isActive || recents.length > 0);

  const choose = (place: GeocodeResult | RecentSearch, full?: GeocodeResult) => {
    if (full) add(full);
    setQuery(place.primary);
    setFocused(false);
    Keyboard.dismiss();
    onSelectPlace({ primary: place.primary, lat: place.lat, lng: place.lng });
  };

  return (
    <View style={[styles.wrap, { top: topOffset }]} pointerEvents="box-none">
      <View
        style={[
          styles.bar,
          {
            backgroundColor: theme.colors.surface,
            borderColor: focused ? theme.colors.primary : theme.colors.border,
            borderRadius: theme.radius.lg,
            ...theme.elevation.floating,
          },
        ]}
      >
        <Ionicons name="search" size={20} color={theme.colors.textMuted} />
        <TextInput
          testID="map.search.input"
          accessibilityLabel="Search for a place"
          value={query}
          onChangeText={setQuery}
          onFocus={() => setFocused(true)}
          placeholder="Search a place or address"
          placeholderTextColor={theme.colors.textMuted}
          returnKeyType="search"
          autoCorrect={false}
          style={[styles.input, { color: theme.colors.text }]}
        />
        {isSearching ? <ActivityIndicator size="small" color={theme.colors.primary} /> : null}
        {query.length > 0 ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Clear search"
            hitSlop={HIT_SLOP}
            onPress={() => {
              setQuery('');
              setFocused(true);
            }}
          >
            <Ionicons name="close-circle" size={20} color={theme.colors.textMuted} />
          </Pressable>
        ) : null}
      </View>

      {showPanel ? (
        <View
          style={[
            styles.panel,
            {
              backgroundColor: theme.colors.surface,
              borderColor: theme.colors.border,
              borderRadius: theme.radius.lg,
              ...theme.elevation.floating,
            },
          ]}
        >
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.panelScroll}>
            {isActive ? (
              <>
                {isError ? (
                  <Row icon="warning-outline" primary="Search is unavailable" secondary="Please try again." muted />
                ) : results.length === 0 && !isSearching ? (
                  <Row icon="information-circle-outline" primary="No places found" muted />
                ) : (
                  results.map((r) => (
                    <Row
                      key={r.id}
                      testID={`map.search.result.${r.id}`}
                      icon="location-outline"
                      primary={r.primary}
                      secondary={r.secondary}
                      onPress={() => choose(r, r)}
                    />
                  ))
                )}
              </>
            ) : (
              <>
                <View style={styles.recentHeader}>
                  <AppText variant="caption" tone="muted">
                    Recent
                  </AppText>
                  <Pressable accessibilityRole="button" accessibilityLabel="Clear recent searches" onPress={clear} hitSlop={HIT_SLOP}>
                    <AppText variant="caption" tone="primary">
                      Clear
                    </AppText>
                  </Pressable>
                </View>
                {recents.map((r) => (
                  <Row
                    key={r.id}
                    icon="time-outline"
                    primary={r.primary}
                    secondary={r.secondary}
                    onPress={() => choose(r)}
                  />
                ))}
              </>
            )}
          </ScrollView>
        </View>
      ) : null}
    </View>
  );
}

function Row({
  icon,
  primary,
  secondary,
  onPress,
  muted,
  testID,
}: {
  icon: keyof typeof Ionicons.glyphMap;
  primary: string;
  secondary?: string;
  onPress?: () => void;
  muted?: boolean;
  testID?: string;
}) {
  const theme = useTheme();
  return (
    <Pressable
      testID={testID}
      accessibilityRole={onPress ? 'button' : 'text'}
      accessibilityLabel={secondary ? `${primary}, ${secondary}` : primary}
      disabled={!onPress}
      onPress={onPress}
      style={({ pressed }) => [
        styles.row,
        { backgroundColor: pressed ? theme.colors.surfaceMuted : 'transparent' },
      ]}
    >
      <Ionicons name={icon} size={18} color={muted ? theme.colors.textMuted : theme.colors.primary} />
      <View style={styles.rowText}>
        <AppText variant="body" numberOfLines={1}>
          {primary}
        </AppText>
        {secondary ? (
          <AppText variant="caption" tone="muted" numberOfLines={1}>
            {secondary}
          </AppText>
        ) : null}
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', left: 12, right: 12 },
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 14,
    height: 50,
    borderWidth: 1,
  },
  input: { flex: 1, fontSize: 16, paddingVertical: 0 },
  panel: { marginTop: 8, borderWidth: 1, overflow: 'hidden' },
  panelScroll: { maxHeight: 280 },
  recentHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingTop: 12,
    paddingBottom: 4,
  },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 14, paddingVertical: 12, minHeight: 44 },
  rowText: { flex: 1 },
});

export const MapSearchBar = memo(MapSearchBarImpl);
