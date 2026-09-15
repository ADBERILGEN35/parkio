import { useCallback, useMemo, useState } from 'react';
import {
  FlatList,
  Modal,
  Pressable,
  StyleSheet,
  View,
} from 'react-native';
import { useQueries } from '@tanstack/react-query';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type {
  AssistantDestinationOrigin,
  Destination,
  FavouriteDestination,
  FavouriteParking,
  QuickActionAvailability,
  QuickActionDescriptor,
  QuickActionKind,
  RecentDestination,
} from '@parkio/types';
import {
  destinationFromFavouriteDestination,
  destinationFromRecentDestination,
  destinationFromSavedPlace,
  resolveHomePlace,
  resolveWorkPlace,
} from '@parkio/validation';
import { AppText } from '@/components/ui/AppText';
import { Chip } from '@/components/ui/Chip';
import { IconButton } from '@/components/ui/IconButton';
import { municipalFacilityDetailQueryOptions } from '@/data/query-options/parking';
import { useT } from '@/i18n/LocaleProvider';
import type { TranslationKey } from '@/i18n/translations';
import {
  trackQuickActionSelected,
  trackQuickActionUnavailable,
} from '@/services/spaTelemetry';
import { useTheme } from '@/theme/ThemeProvider';
import { useQuickActionSources } from './useQuickActionSources';

export type QuickActionsRowProps = {
  enabled: boolean;
  visible?: boolean;
  onSelectDestination: (destination: Destination, origin: AssistantDestinationOrigin) => void;
  onOpenSearch: () => void;
  onParkedCar: () => void;
  onSelectFavouriteParking: (facilityId: string) => void;
};

type PickerKind = 'favourite_destinations' | 'favourite_parking' | 'recent_destinations' | null;

function chipIcon(kind: QuickActionKind): keyof typeof import('@expo/vector-icons').MaterialCommunityIcons.glyphMap {
  switch (kind) {
    case 'HOME':
      return 'home-outline';
    case 'WORK':
      return 'briefcase-outline';
    case 'PARKED_CAR':
      return 'car-outline';
    case 'FAVOURITE_DESTINATIONS':
      return 'star-outline';
    case 'FAVOURITE_PARKING':
      return 'parking';
    case 'RECENT_DESTINATIONS':
      return 'history';
    default:
      return 'lightning-bolt-outline';
  }
}

function labelKey(kind: QuickActionKind, availability: QuickActionAvailability): TranslationKey {
  if (kind === 'HOME' && availability === 'UNCONFIGURED') return 'assistant.quickActions.addHome';
  if (kind === 'WORK' && availability === 'UNCONFIGURED') return 'assistant.quickActions.addWork';
  switch (kind) {
    case 'HOME':
      return 'assistant.quickActions.home';
    case 'WORK':
      return 'assistant.quickActions.work';
    case 'PARKED_CAR':
      return 'assistant.quickActions.parkedCar';
    case 'FAVOURITE_DESTINATIONS':
      return 'assistant.quickActions.favouriteDestinations';
    case 'FAVOURITE_PARKING':
      return 'assistant.quickActions.favouriteParking';
    case 'RECENT_DESTINATIONS':
      return 'assistant.quickActions.recentDestinations';
    default:
      return 'assistant.quickActions.groupLabel';
  }
}

function isPressDisabled(availability: QuickActionAvailability): boolean {
  return (
    availability === 'EMPTY' ||
    availability === 'ERROR' ||
    availability === 'LOADING' ||
    availability === 'UNAVAILABLE'
  );
}

export function QuickActionsRow({
  enabled,
  visible = true,
  onSelectDestination,
  onOpenSearch,
  onParkedCar,
  onSelectFavouriteParking,
}: QuickActionsRowProps) {
  const t = useT();
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const sources = useQuickActionSources({ enabled });
  const [picker, setPicker] = useState<PickerKind>(null);

  const parkingIds = useMemo(
    () =>
      picker === 'favourite_parking'
        ? (sources.favouriteParking.data ?? []).map((f) => f.targetId)
        : [],
    [picker, sources.favouriteParking.data],
  );

  const facilityQueries = useQueries({
    queries: parkingIds.map((id) => ({
      ...municipalFacilityDetailQueryOptions(id),
      // Allow hydrate even when municipal discovery flag is off — favourite is user-owned.
      enabled: picker === 'favourite_parking' && id.length > 0,
      retry: false,
    })),
  });

  const handleAction = useCallback(
    (descriptor: QuickActionDescriptor) => {
      const { kind, availability } = descriptor;
      if (kind === 'HOME') {
        if (availability === 'UNCONFIGURED') {
          trackQuickActionUnavailable(kind, availability);
          onOpenSearch();
          return;
        }
        if (availability !== 'AVAILABLE') {
          trackQuickActionUnavailable(kind, availability);
          return;
        }
        const place = resolveHomePlace(sources.snapshot.savedPlaces);
        if (!place) return;
        trackQuickActionSelected(kind, availability);
        onSelectDestination(destinationFromSavedPlace(place), 'HOME_QUICK_ACTION');
        return;
      }
      if (kind === 'WORK') {
        if (availability === 'UNCONFIGURED') {
          trackQuickActionUnavailable(kind, availability);
          onOpenSearch();
          return;
        }
        if (availability !== 'AVAILABLE') {
          trackQuickActionUnavailable(kind, availability);
          return;
        }
        const place = resolveWorkPlace(sources.snapshot.savedPlaces);
        if (!place) return;
        trackQuickActionSelected(kind, availability);
        onSelectDestination(destinationFromSavedPlace(place), 'WORK_QUICK_ACTION');
        return;
      }
      if (kind === 'PARKED_CAR') {
        if (availability !== 'AVAILABLE') {
          trackQuickActionUnavailable(kind, availability);
          return;
        }
        trackQuickActionSelected(kind, availability);
        onParkedCar();
        return;
      }
      if (kind === 'FAVOURITE_DESTINATIONS') {
        if (availability !== 'AVAILABLE') {
          trackQuickActionUnavailable(kind, availability);
          return;
        }
        trackQuickActionSelected(kind, availability);
        const items = sources.favouriteDestinations.data ?? [];
        if (items.length === 1) {
          onSelectDestination(
            destinationFromFavouriteDestination(items[0]!),
            'FAVOURITE_DESTINATION_QUICK_ACTION',
          );
          return;
        }
        setPicker('favourite_destinations');
        return;
      }
      if (kind === 'FAVOURITE_PARKING') {
        if (availability !== 'AVAILABLE') {
          trackQuickActionUnavailable(kind, availability);
          return;
        }
        trackQuickActionSelected(kind, availability);
        setPicker('favourite_parking');
        return;
      }
      if (kind === 'RECENT_DESTINATIONS') {
        if (availability !== 'AVAILABLE') {
          trackQuickActionUnavailable(kind, availability);
          return;
        }
        trackQuickActionSelected(kind, availability);
        const items = sources.recentDestinations.data ?? [];
        if (items.length === 1) {
          onSelectDestination(
            destinationFromRecentDestination(items[0]!),
            'RECENT_DESTINATION_QUICK_ACTION',
          );
          return;
        }
        setPicker('recent_destinations');
      }
    },
    [
      onOpenSearch,
      onParkedCar,
      onSelectDestination,
      sources.favouriteDestinations.data,
      sources.recentDestinations.data,
      sources.snapshot.savedPlaces,
    ],
  );

  if (!enabled || !visible) return null;

  const pickerTitle =
    picker === 'favourite_destinations'
      ? t('assistant.quickActions.favouriteDestinations')
      : picker === 'favourite_parking'
        ? t('assistant.quickActions.favouriteParking')
        : t('assistant.quickActions.recentDestinations');

  const pickerData: {
    key: string;
    title: string;
    subtitle?: string | null;
    disabled?: boolean;
    onPress: () => void;
  }[] =
    picker === 'favourite_destinations'
      ? (sources.favouriteDestinations.data ?? []).map((item: FavouriteDestination) => ({
          key: item.id,
          title: item.label,
          subtitle: item.subtitle,
          onPress: () => {
            setPicker(null);
            onSelectDestination(
              destinationFromFavouriteDestination(item),
              'FAVOURITE_DESTINATION_QUICK_ACTION',
            );
          },
        }))
      : picker === 'recent_destinations'
        ? (sources.recentDestinations.data ?? []).map((item: RecentDestination) => ({
            key: item.id,
            title: item.label,
            subtitle: item.subtitle,
            onPress: () => {
              setPicker(null);
              onSelectDestination(
                destinationFromRecentDestination(item),
                'RECENT_DESTINATION_QUICK_ACTION',
              );
            },
          }))
        : picker === 'favourite_parking'
          ? (sources.favouriteParking.data ?? []).map((fav: FavouriteParking, index: number) => {
              const q = facilityQueries[index];
              const name = q?.data?.displayName ?? q?.data?.operatorName;
              const unavailable = Boolean(q?.isError);
              return {
                key: fav.id,
                title: name ?? t('assistant.quickActions.parkingFallback'),
                subtitle: unavailable
                  ? t('assistant.quickActions.unavailable')
                  : q?.isPending
                    ? t('assistant.quickActions.loading')
                    : q?.data?.addressText ?? null,
                disabled: unavailable || Boolean(q?.isPending),
                onPress: () => {
                  if (unavailable || q?.isPending) return;
                  setPicker(null);
                  onSelectFavouriteParking(fav.targetId);
                },
              };
            })
          : [];

  return (
    <View style={styles.wrap} testID="assistant-quick-actions">
      <AppText variant="labelSm" color={colors.onSurfaceVariant} style={styles.heading}>
        {t('assistant.quickActions.groupLabel')}
      </AppText>
      <View style={styles.row}>
        {sources.descriptors.map((descriptor) => {
          const disabled =
            isPressDisabled(descriptor.availability) &&
            descriptor.availability !== 'UNCONFIGURED';
          const label = t(labelKey(descriptor.kind, descriptor.availability));
          return (
            <Chip
              key={descriptor.kind}
              label={label}
              icon={chipIcon(descriptor.kind)}
              disabled={disabled}
              numberOfLines={2}
              onPress={() => handleAction(descriptor)}
              style={styles.chip}
            />
          );
        })}
      </View>

      <Modal
        visible={picker != null}
        animationType="slide"
        presentationStyle="pageSheet"
        onRequestClose={() => setPicker(null)}
      >
        <View
          style={[
            styles.pickerRoot,
            { backgroundColor: colors.surface, paddingTop: insets.top + 8 },
          ]}
        >
          <View style={styles.pickerHeader}>
            <AppText variant="titleMd" accessibilityRole="header">
              {pickerTitle}
            </AppText>
            <IconButton
              icon="close"
              size={40}
              variant="glassless"
              accessibilityLabel={t('common.close')}
              onPress={() => setPicker(null)}
            />
          </View>
          <FlatList
            data={pickerData}
            keyExtractor={(item) => item.key}
            contentContainerStyle={styles.pickerList}
            renderItem={({ item }) => (
              <Pressable
                onPress={item.onPress}
                disabled={item.disabled}
                accessibilityRole="button"
                accessibilityLabel={item.title}
                style={({ pressed }) => [
                  styles.pickerRow,
                  {
                    backgroundColor: pressed ? colors.surfaceContainer2 : 'transparent',
                    opacity: item.disabled ? 0.45 : 1,
                  },
                ]}
              >
                <AppText variant="bodyMd" numberOfLines={2}>
                  {item.title}
                </AppText>
                {item.subtitle ? (
                  <AppText variant="bodySm" color={colors.onSurfaceVariant} numberOfLines={1}>
                    {item.subtitle}
                  </AppText>
                ) : null}
              </Pressable>
            )}
          />
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: 6, marginTop: 8 },
  heading: { marginHorizontal: 4 },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: { maxWidth: '100%' },
  pickerRoot: { flex: 1 },
  pickerHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingBottom: 8,
  },
  pickerList: { paddingHorizontal: 8, paddingBottom: 32 },
  pickerRow: {
    minHeight: 56,
    paddingHorizontal: 12,
    paddingVertical: 12,
    borderRadius: 12,
    gap: 2,
  },
});
