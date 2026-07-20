import { useState } from 'react';
import { Image, Pressable, StyleSheet, View } from 'react-native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { PARKING_CONTEXTS, SPOT_VEHICLE_TYPES, VIOLATION_REASONS } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Card } from '@/components/ui/Card';
import { Chip } from '@/components/ui/Chip';
import { OptionSheet } from '@/components/ui/OptionSheet';
import { SegmentedControl } from '@/components/ui/SegmentedControl';
import { TextArea } from '@/components/ui/TextArea';
import { CONTEXT_ICONS, VEHICLE_ICONS } from '@/components/spots/spotChips';
import { useT } from '@/i18n/LocaleProvider';
import { radius } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';
import { useShareDraftStore } from '../state/shareDraftStore';

export interface DetailsStepProps {
  onEditLocation: () => void;
}

/**
 * Step 3 — description, vehicle fit, context, legal status (Riskli hard-block)
 * and optional advisory flags (pen `B2qzu`, brief §12.5.7).
 */
export function DetailsStep({ onEditLocation }: DetailsStepProps) {
  const theme = useTheme();
  const t = useT();
  const { colors } = theme;
  const [contextSheetOpen, setContextSheetOpen] = useState(false);

  const photo = useShareDraftStore((s) => s.photo);
  const addressText = useShareDraftStore((s) => s.addressText);
  const location = useShareDraftStore((s) => s.location);
  const manualEdited = useShareDraftStore((s) => s.manualLocationEdited);
  const description = useShareDraftStore((s) => s.description);
  const vehicleTypes = useShareDraftStore((s) => s.vehicleTypes);
  const parkingContext = useShareDraftStore((s) => s.parkingContext);
  const legalStatus = useShareDraftStore((s) => s.legalStatus);
  const violationReasons = useShareDraftStore((s) => s.violationReasons);
  const setDescription = useShareDraftStore((s) => s.setDescription);
  const toggleVehicleType = useShareDraftStore((s) => s.toggleVehicleType);
  const setParkingContext = useShareDraftStore((s) => s.setParkingContext);
  const setLegalStatus = useShareDraftStore((s) => s.setLegalStatus);
  const toggleViolationReason = useShareDraftStore((s) => s.toggleViolationReason);

  return (
    <View style={styles.container}>
      {/* Location summary (edit link back to step 2). */}
      <Pressable onPress={onEditLocation} accessibilityRole="button" accessibilityLabel={t('share.step.location')}>
        <Card tone={1} padding={10}>
          <View style={styles.summaryRow}>
            <View style={[styles.thumb, { backgroundColor: colors.surfaceContainer3 }]}>
              {photo ? (
                <Image source={{ uri: photo.uri }} style={styles.thumbImage} resizeMode="cover" />
              ) : (
                <MaterialCommunityIcons name="image-outline" size={18} color={colors.outline} />
              )}
            </View>
            <View style={styles.summaryLabels}>
              <AppText variant="bodySm" numberOfLines={1}>
                {addressText.trim() || t('spot.address.unknown')}
              </AppText>
              <AppText variant="labelSm" tabular color={colors.onSurfaceVariant} numberOfLines={1}>
                {location
                  ? `${location.latitude.toFixed(5)}, ${location.longitude.toFixed(5)}${
                      manualEdited ? ` · ${t('share.location.manualEdited')}` : ''
                    }`
                  : t('share.gps.searching')}
              </AppText>
            </View>
            <MaterialCommunityIcons name="pencil-outline" size={17} color={colors.primary} />
          </View>
        </Card>
      </Pressable>

      <TextArea
        label={t('share.details.description')}
        placeholder={t('share.details.descriptionPlaceholder')}
        value={description}
        onChangeText={setDescription}
        maxLength={1000}
      />

      {/* Vehicle fit */}
      <View style={styles.group}>
        <AppText variant="bodySm" color={colors.onSurfaceVariant}>
          {t('share.details.vehicleFit')}
        </AppText>
        <View style={styles.chipWrap}>
          {SPOT_VEHICLE_TYPES.map((vehicle) => (
            <Chip
              key={vehicle}
              icon={VEHICLE_ICONS[vehicle]}
              label={t(`vehicle.${vehicle}`)}
              selected={vehicleTypes.includes(vehicle)}
              onPress={() => toggleVehicleType(vehicle)}
            />
          ))}
        </View>
      </View>

      {/* Context select */}
      <View style={styles.group}>
        <AppText variant="bodySm" color={colors.onSurfaceVariant}>
          {t('share.details.context')}
        </AppText>
        <Pressable
          onPress={() => setContextSheetOpen(true)}
          accessibilityRole="button"
          accessibilityLabel={t('share.details.context')}
          style={[
            styles.selectField,
            { borderColor: colors.outlineVariant, backgroundColor: colors.surface, borderRadius: radius.input },
          ]}
        >
          <MaterialCommunityIcons name={CONTEXT_ICONS[parkingContext]} size={18} color={colors.onSurfaceVariant} />
          <AppText variant="bodyLg" style={styles.selectValue}>
            {t(`context.${parkingContext}`)}
          </AppText>
          <MaterialCommunityIcons name="chevron-down" size={20} color={colors.outline} />
        </Pressable>
      </View>

      {/* Legal status */}
      <View style={styles.group}>
        <AppText variant="bodySm" color={colors.onSurfaceVariant}>
          {t('share.details.legal')}
        </AppText>
        <SegmentedControl
          options={[
            { value: 'LEGAL', label: t('share.details.legal.LEGAL') },
            { value: 'UNCERTAIN', label: t('share.details.legal.UNCERTAIN') },
            { value: 'ILLEGAL_OR_RISKY', label: t('share.details.legal.ILLEGAL_OR_RISKY'), tone: 'danger' },
          ]}
          value={legalStatus}
          onChange={setLegalStatus}
        />
        {legalStatus === 'ILLEGAL_OR_RISKY' && (
          <Card tone={0} padding={14} style={[styles.riskyCard, { backgroundColor: colors.errorContainer }]} shadow={false}>
            <View style={styles.riskyRow}>
              <MaterialCommunityIcons name="alert-octagon-outline" size={22} color={colors.error} />
              <View style={styles.riskyLabels}>
                <AppText variant="titleMd" color={colors.error}>
                  {t('share.details.riskyBlock.title')}
                </AppText>
                <AppText variant="bodySm" color={theme.mode === 'dark' ? colors.onSurface : '#5C1210'}>
                  {t('share.details.riskyBlock.body')}
                </AppText>
              </View>
            </View>
          </Card>
        )}
      </View>

      {/* Advisory flags */}
      <View style={styles.group}>
        <AppText variant="bodySm" color={colors.onSurfaceVariant}>
          {t('share.details.violations')}
        </AppText>
        <View style={styles.chipWrap}>
          {VIOLATION_REASONS.map((reason) => (
            <Chip
              key={reason}
              label={t(`violation.${reason}`)}
              selected={violationReasons.includes(reason)}
              onPress={() => toggleViolationReason(reason)}
              size="sm"
            />
          ))}
        </View>
      </View>

      <OptionSheet
        visible={contextSheetOpen}
        onClose={() => setContextSheetOpen(false)}
        title={t('share.details.context')}
        options={PARKING_CONTEXTS.map((context) => ({
          value: context,
          label: t(`context.${context}`),
          icon: CONTEXT_ICONS[context],
        }))}
        selected={parkingContext}
        onSelect={(context) => {
          setParkingContext(context);
          setContextSheetOpen(false);
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: 14 },
  summaryRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  thumb: {
    width: 40,
    height: 40,
    borderRadius: 10,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
  },
  thumbImage: { width: 40, height: 40 },
  summaryLabels: { flex: 1, gap: 1 },
  group: { gap: 8 },
  chipWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  selectField: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    borderWidth: 1,
    paddingHorizontal: 14,
    minHeight: 50,
  },
  selectValue: { flex: 1 },
  riskyCard: {},
  riskyRow: { flexDirection: 'row', gap: 10, alignItems: 'flex-start' },
  riskyLabels: { flex: 1, gap: 3 },
});
