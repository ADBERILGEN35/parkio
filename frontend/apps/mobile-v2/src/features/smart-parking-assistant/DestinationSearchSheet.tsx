import { useMemo, useState } from 'react';
import {
  FlatList,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useQuery } from '@tanstack/react-query';
import type {
  DestinationSearchItem,
  DestinationSearchSource,
  SavedPlaceKind,
} from '@parkio/types';
import { composeDestinationSearch } from '@parkio/validation';
import { AppText } from '@/components/ui/AppText';
import { IconButton } from '@/components/ui/IconButton';
import { Skeleton } from '@/components/ui/Skeleton';
import {
  favouriteDestinationsQueryOptions,
  recentDestinationsQueryOptions,
  savedPlacesQueryOptions,
} from '@/data/query-options/places';
import { usePlaceSearch } from '@/features/map/hooks';
import { useT } from '@/i18n/LocaleProvider';
import type { TranslationKey } from '@/i18n/translations';
import { fonts } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

const GEOCODE_MIN = 3;

export type DestinationSearchSheetProps = {
  open: boolean;
  onClose: () => void;
  onSelect: (item: DestinationSearchItem) => void;
};

function sourceSectionKey(source: DestinationSearchSource): TranslationKey {
  switch (source) {
    case 'SAVED_PLACE':
      return 'assistant.sections.saved';
    case 'FAVOURITE_DESTINATION':
      return 'assistant.sections.favourites';
    case 'RECENT_DESTINATION':
      return 'assistant.sections.recents';
    case 'GEOCODING':
    default:
      return 'assistant.sections.geocoding';
  }
}

function kindLabelKey(kind: SavedPlaceKind | null | undefined): TranslationKey | null {
  if (kind === 'HOME') return 'assistant.kinds.home';
  if (kind === 'WORK') return 'assistant.kinds.work';
  if (kind === 'CUSTOM') return 'assistant.kinds.custom';
  return null;
}

function sourceBadgeKey(item: DestinationSearchItem): TranslationKey | null {
  if (item.source === 'FAVOURITE_DESTINATION' || item.alsoFavourite) {
    return 'assistant.kinds.favourite';
  }
  if (item.source === 'RECENT_DESTINATION' || item.alsoRecent) {
    return 'assistant.kinds.recent';
  }
  return null;
}

function rowIcon(item: DestinationSearchItem): keyof typeof MaterialCommunityIcons.glyphMap {
  if (item.savedPlaceKind === 'HOME') return 'home-outline';
  if (item.savedPlaceKind === 'WORK') return 'briefcase-outline';
  if (item.source === 'FAVOURITE_DESTINATION' || item.alsoFavourite) return 'star-outline';
  if (item.source === 'RECENT_DESTINATION' || item.alsoRecent) return 'history';
  return 'map-marker-outline';
}

export function DestinationSearchSheet({ open, onClose, onSelect }: DestinationSearchSheetProps) {
  // Remount body when opened so draft query resets without effect setState.
  return (
    <Modal
      visible={open}
      animationType="slide"
      presentationStyle="pageSheet"
      onRequestClose={onClose}
    >
      {open ? <DestinationSearchBody onClose={onClose} onSelect={onSelect} /> : null}
    </Modal>
  );
}

function DestinationSearchBody({
  onClose,
  onSelect,
}: {
  onClose: () => void;
  onSelect: (item: DestinationSearchItem) => void;
}) {
  const t = useT();
  const insets = useSafeAreaInsets();
  const { colors } = useTheme();
  const [query, setQuery] = useState('');

  const saved = useQuery({ ...savedPlacesQueryOptions(), enabled: true });
  const favourites = useQuery({ ...favouriteDestinationsQueryOptions(), enabled: true });
  const recents = useQuery({ ...recentDestinationsQueryOptions(), enabled: true });
  const places = usePlaceSearch(query);

  const geocodingResults = useMemo(
    () =>
      (places.data ?? []).map((r) => ({
        label: r.primary,
        latitude: r.lat,
        longitude: r.lng,
        placeIdentity: null,
        subtitle: r.secondary ?? null,
      })),
    [places.data],
  );

  const composed = useMemo(
    () =>
      composeDestinationSearch({
        query,
        savedPlaces: saved.data ?? [],
        favouriteDestinations: favourites.data ?? [],
        recentDestinations: recents.data ?? [],
        geocodingResults,
        geocodeMinLength: GEOCODE_MIN,
      }),
    [favourites.data, geocodingResults, query, recents.data, saved.data],
  );

  const showGeocodeLoading = query.trim().length >= GEOCODE_MIN && places.isFetching;
  const emptyActive =
    query.trim().length >= GEOCODE_MIN &&
    !showGeocodeLoading &&
    composed.items.length === 0;

  return (
    <KeyboardAvoidingView
      style={[styles.root, { backgroundColor: colors.surface, paddingTop: insets.top }]}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <View style={styles.header}>
        <AppText variant="titleMd" accessibilityRole="header">
          {t('assistant.searchTitle')}
        </AppText>
        <IconButton
          icon="close"
          size={40}
          variant="glassless"
          accessibilityLabel={t('common.close')}
          onPress={onClose}
        />
      </View>

      <View
        style={[
          styles.inputRow,
          { backgroundColor: colors.surfaceContainer2, borderColor: colors.outlineVariant },
        ]}
      >
        <MaterialCommunityIcons name="magnify" size={20} color={colors.onSurfaceVariant} />
        <TextInput
          value={query}
          onChangeText={setQuery}
          placeholder={t('assistant.searchPlaceholder')}
          placeholderTextColor={colors.outline}
          style={[styles.input, { color: colors.onSurface, fontFamily: fonts.regular }]}
          autoFocus
          returnKeyType="search"
          accessibilityLabel={t('assistant.searchPlaceholder')}
          testID="assistant-destination-search"
        />
        {query.length > 0 ? (
          <IconButton
            icon="close"
            size={32}
            variant="glassless"
            accessibilityLabel={t('common.close')}
            onPress={() => setQuery('')}
          />
        ) : null}
      </View>

      {showGeocodeLoading ? (
        <View style={styles.loadingBlock}>
          <Skeleton height={48} radius={12} />
          <Skeleton height={48} radius={12} />
        </View>
      ) : null}

      {emptyActive ? (
        <AppText variant="bodyMd" color={colors.onSurfaceVariant} style={styles.empty}>
          {t('assistant.searchEmpty')}
        </AppText>
      ) : null}

      <FlatList
        data={composed.items}
        keyExtractor={(item, index) => `${item.source}:${item.destination.label}:${index}`}
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={styles.list}
        renderItem={({ item, index }) => {
          const prev = composed.items[index - 1];
          const showSection = !prev || prev.source !== item.source;
          const kindKey = kindLabelKey(item.savedPlaceKind);
          const badgeKey = sourceBadgeKey(item);
          const context = kindKey ? t(kindKey) : badgeKey ? t(badgeKey) : t(sourceSectionKey(item.source));
          const a11y = `${item.destination.label}. ${context}`;

          return (
            <View>
              {showSection ? (
                <AppText
                  variant="labelSm"
                  color={colors.onSurfaceVariant}
                  style={styles.section}
                  accessibilityRole="header"
                >
                  {t(sourceSectionKey(item.source))}
                </AppText>
              ) : null}
              <Pressable
                onPress={() => onSelect(item)}
                accessibilityRole="button"
                accessibilityLabel={a11y}
                style={({ pressed }) => [
                  styles.row,
                  { backgroundColor: pressed ? colors.surfaceContainer2 : 'transparent' },
                ]}
              >
                <MaterialCommunityIcons name={rowIcon(item)} size={22} color={colors.primary} />
                <View style={styles.rowText}>
                  <AppText variant="bodyMd" numberOfLines={2}>
                    {item.destination.label}
                  </AppText>
                  <AppText variant="bodySm" color={colors.onSurfaceVariant} numberOfLines={1}>
                    {[context, item.destination.subtitle].filter(Boolean).join(' · ')}
                  </AppText>
                </View>
              </Pressable>
            </View>
          );
        }}
      />
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginHorizontal: 16,
    marginBottom: 8,
    paddingHorizontal: 12,
    borderRadius: 14,
    borderWidth: StyleSheet.hairlineWidth,
    minHeight: 48,
  },
  input: { flex: 1, fontSize: 16, paddingVertical: 10 },
  list: { paddingBottom: 32, paddingHorizontal: 8 },
  section: { marginTop: 12, marginBottom: 4, marginHorizontal: 8 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 12,
    paddingVertical: 12,
    minHeight: 56,
    borderRadius: 12,
  },
  rowText: { flex: 1, gap: 2 },
  loadingBlock: { gap: 8, paddingHorizontal: 16, marginBottom: 8 },
  empty: { paddingHorizontal: 20, paddingVertical: 16 },
});
