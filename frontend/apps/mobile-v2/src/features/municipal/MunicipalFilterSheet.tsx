import { ScrollView, StyleSheet, Switch, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { AppText } from '@/components/ui/AppText';
import { Chip } from '@/components/ui/Chip';
import { PressableScale } from '@/components/ui/PressableScale';
import { Sheet } from '@/components/ui/Sheet';
import { useT } from '@/i18n/LocaleProvider';
import { radius } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';
import {
  MUNICIPAL_CANONICAL_LABEL_IZUM,
  MUNICIPAL_CANONICAL_LABEL_ISPARK,
  MUNICIPAL_CANONICAL_LABEL_ANPARK,
  MUNICIPAL_CANONICAL_LABEL_KONYA,
  MUNICIPAL_CANONICAL_LABEL_KAYSERI,
  MUNICIPAL_CANONICAL_LABEL_OSM,
} from '@parkio/geo';
import {
  MUNICIPAL_RADIUS_OPTIONS_M,
  countActiveMunicipalFilters,
  formatMunicipalRadiusLabel,
  hasActiveMunicipalMapFilters,
  type MunicipalMapFilters,
  type MunicipalOccupancyFilter,
  type MunicipalRadiusMeters,
  type MunicipalSourceFilter,
} from './municipalFilterModel';

export interface MunicipalFilterSheetProps {
  visible: boolean;
  onClose: () => void;
  filters: MunicipalMapFilters;
  onLayerEnabledChange: (enabled: boolean) => void;
  onSourceChange: (source: MunicipalSourceFilter) => void;
  onOccupancyChange: (occupancy: MunicipalOccupancyFilter) => void;
  onRadiusChange: (radiusMeters: MunicipalRadiusMeters) => void;
  onReset: () => void;
}

/**
 * Municipal discovery filter sheet — layer, source, occupancy, radius, reset.
 * Source/occupancy apply immediately; radius commits on chip press.
 */
export function MunicipalFilterSheet({
  visible,
  onClose,
  filters,
  onLayerEnabledChange,
  onSourceChange,
  onOccupancyChange,
  onRadiusChange,
  onReset,
}: MunicipalFilterSheetProps) {
  const t = useT();
  const theme = useTheme();
  const { colors } = theme;
  const canReset = hasActiveMunicipalMapFilters(filters);

  const sourceOptions: { value: MunicipalSourceFilter; label: string }[] = [
    { value: 'all', label: t('map.municipal.filters.source.all') },
    { value: 'izum', label: MUNICIPAL_CANONICAL_LABEL_IZUM },
    { value: 'ispark', label: MUNICIPAL_CANONICAL_LABEL_ISPARK },
    { value: 'anpark', label: MUNICIPAL_CANONICAL_LABEL_ANPARK },
    { value: 'konya', label: MUNICIPAL_CANONICAL_LABEL_KONYA },
    { value: 'kayseri', label: MUNICIPAL_CANONICAL_LABEL_KAYSERI },
    { value: 'osm', label: MUNICIPAL_CANONICAL_LABEL_OSM },
  ];

  const occupancyOptions: { value: MunicipalOccupancyFilter; label: string }[] = [
    { value: 'all', label: t('map.municipal.filters.occupancy.all') },
    { value: 'live', label: t('map.municipal.filters.occupancy.live') },
    { value: 'static', label: t('map.municipal.filters.occupancy.static') },
  ];

  return (
    <Sheet visible={visible} onClose={onClose} title={t('map.municipal.filters.title')}>
      <ScrollView bounces={false} style={styles.scroll} contentContainerStyle={styles.scrollInner}>
        <View
          style={[styles.layerRow, { backgroundColor: colors.surfaceContainer1, borderRadius: radius.input + 4 }]}
          accessibilityRole="switch"
          accessibilityState={{ checked: filters.layerEnabled }}
          accessibilityLabel={t('map.municipal.filters.layerLabel')}
        >
          <View style={styles.layerCopy}>
            <AppText variant="bodyLg" color={colors.onSurface}>
              {t('map.municipal.filters.layerLabel')}
            </AppText>
            <AppText variant="bodySm" color={colors.onSurfaceVariant}>
              {filters.layerEnabled
                ? t('map.municipal.filters.layerOnHint')
                : t('map.municipal.filters.layerOffHint')}
            </AppText>
          </View>
          <Switch
            value={filters.layerEnabled}
            onValueChange={onLayerEnabledChange}
            accessibilityLabel={t('map.municipal.filters.layerLabel')}
          />
        </View>

        <AppText variant="labelSm" color={colors.onSurfaceVariant} style={styles.sectionLabel}>
          {t('map.municipal.filters.sourceSection')}
        </AppText>
        <View style={styles.chipWrap} accessibilityRole="radiogroup">
          {sourceOptions.map((option) => (
            <Chip
              key={option.value}
              label={option.label}
              size="sm"
              numberOfLines={2}
              selected={filters.source === option.value}
              onPress={() => onSourceChange(option.value)}
              style={styles.chip}
            />
          ))}
        </View>

        <AppText variant="labelSm" color={colors.onSurfaceVariant} style={styles.sectionLabel}>
          {t('map.municipal.filters.occupancySection')}
        </AppText>
        <View style={styles.chipWrap} accessibilityRole="radiogroup">
          {occupancyOptions.map((option) => (
            <Chip
              key={option.value}
              label={option.label}
              size="sm"
              selected={filters.occupancy === option.value}
              onPress={() => onOccupancyChange(option.value)}
              style={styles.chip}
            />
          ))}
        </View>

        <AppText variant="labelSm" color={colors.onSurfaceVariant} style={styles.sectionLabel}>
          {t('map.municipal.filters.radiusSection')}
        </AppText>
        <View style={styles.chipWrap} accessibilityRole="radiogroup">
          {MUNICIPAL_RADIUS_OPTIONS_M.map((meters) => (
            <Chip
              key={meters}
              label={formatMunicipalRadiusLabel(meters)}
              size="sm"
              selected={filters.radiusMeters === meters}
              onPress={() => onRadiusChange(meters)}
              style={styles.chip}
            />
          ))}
        </View>

        {canReset ? (
          <PressableScale
            onPress={onReset}
            accessibilityRole="button"
            accessibilityLabel={t('map.municipal.filters.reset')}
            style={[styles.reset, { backgroundColor: colors.surfaceContainer1, borderRadius: radius.input + 4 }]}
          >
            <MaterialCommunityIcons name="filter-off-outline" size={18} color={colors.primary} />
            <AppText variant="bodyLg" color={colors.primary}>
              {t('map.municipal.filters.reset')}
            </AppText>
          </PressableScale>
        ) : null}

        <AppText variant="bodySm" color={colors.onSurfaceVariant} style={styles.hint}>
          {t('map.municipal.filters.hint')}
        </AppText>
      </ScrollView>
    </Sheet>
  );
}

export interface MunicipalFilterEntryProps {
  filters: MunicipalMapFilters;
  onPress: () => void;
}

/** Compact map chrome entry — filter icon + optional active count. */
export function MunicipalFilterEntry({ filters, onPress }: MunicipalFilterEntryProps) {
  const t = useT();
  const theme = useTheme();
  const { colors } = theme;
  const active = countActiveMunicipalFilters(filters);
  const label =
    active > 0
      ? t('map.municipal.filters.entryActive', { count: active })
      : t('map.municipal.filters.entry');

  return (
    <PressableScale
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={t('map.municipal.filters.entryHint')}
      style={[
        styles.entry,
        {
          backgroundColor: active > 0 || !filters.layerEnabled ? colors.primaryFixed : colors.surfaceContainer1,
          borderRadius: radius.pill,
        },
      ]}
    >
      <MaterialCommunityIcons
        name={filters.layerEnabled ? 'office-building-marker-outline' : 'eye-off-outline'}
        size={16}
        color={colors.primary}
      />
      <AppText variant="labelSm" color={colors.onSurface} numberOfLines={1}>
        {active > 0 ? t('map.municipal.filters.entryCount', { count: active }) : t('map.municipal.filters.entryShort')}
      </AppText>
    </PressableScale>
  );
}

const styles = StyleSheet.create({
  scroll: { maxHeight: 480 },
  scrollInner: { gap: 10, paddingBottom: 8 },
  layerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  layerCopy: { flex: 1, gap: 2 },
  sectionLabel: { marginTop: 4 },
  chipWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: { maxWidth: '100%' },
  reset: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    paddingVertical: 12,
    marginTop: 4,
  },
  hint: { marginTop: 2 },
  entry: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 12,
    paddingVertical: 7,
    alignSelf: 'flex-start',
  },
});
