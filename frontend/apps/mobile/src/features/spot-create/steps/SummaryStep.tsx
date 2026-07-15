import { Ionicons } from '@expo/vector-icons';
import type { ReactNode } from 'react';
import { Image, Pressable, StyleSheet, View } from 'react-native';
import type { ParkingContext, SpotVehicleType } from '@parkio/types';
import { AppText, Button } from '@/components/ui';
import { SelectedLocationCard } from '@/features/spot-create/components/SelectedLocationCard';
import { useSelectedPlace } from '@/features/spot-create/hooks/useSelectedPlace';
import type { SpotCreationWizardStep } from '@/features/spot-create/lib/wizardSteps';
import { useSpotCreationDraftStore } from '@/features/spot-create/state/spotCreationDraftStore';
import { useLocale } from '@/i18n/LocaleProvider';
import { HIT_SLOP, useTheme } from '@/theme';

const VEHICLE_LABEL_KEYS: Record<SpotVehicleType, string> = {
  ANY: 'Any',
  SEDAN: 'Sedan',
  HATCHBACK: 'Hatchback',
  SUV: 'SUV',
  VAN: 'Van',
  MOTORCYCLE: 'Motorcycle',
};

const PARKING_LABEL_KEYS: Record<ParkingContext, string> = {
  STREET_PARKING: 'Street',
  OPEN_PARKING_LOT: 'Open lot',
  INDOOR_PARKING: 'Indoor',
  MALL_PARKING: 'Mall',
  RESIDENTIAL_AREA: 'Residential',
  OFFICE_AREA: 'Office',
  UNKNOWN: 'Unsure',
};

export interface SummaryStepProps {
  onEditPhoto: () => void;
  onEditStep: (step: SpotCreationWizardStep) => void;
  onShare: () => void;
  canShare: boolean;
  isSharing: boolean;
  errorMessage: string | null;
}

/** Wizard step 4: review photo, location, details; Share CTA and edit shortcuts. */
export function SummaryStep({
  onEditPhoto,
  onEditStep,
  onShare,
  canShare,
  isSharing,
  errorMessage,
}: SummaryStepProps) {
  const theme = useTheme();
  const { t } = useLocale();
  const draft = useSpotCreationDraftStore((state) => state.draft);
  const selectedPlace = useSelectedPlace(draft?.location ?? null);

  if (!draft) return null;

  return (
    <View style={styles.root} testID="wizard-summary-step">
      <View style={[styles.previewFrame, { borderColor: theme.colors.border, borderRadius: theme.radius.lg }]}>
        <Image
          source={{ uri: draft.previewUri }}
          style={styles.previewImage}
          resizeMode="cover"
          accessibilityLabel={t('Photo of the parking spot')}
        />
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('Change photo')}
          hitSlop={HIT_SLOP}
          onPress={onEditPhoto}
          style={({ pressed }) => [
            styles.changePhoto,
            {
              backgroundColor: pressed ? theme.colors.surfaceMuted : theme.colors.surface,
              borderRadius: theme.radius.full,
              ...theme.elevation.card,
            },
          ]}
        >
          <Ionicons name="camera-outline" size={16} color={theme.colors.primary} />
          <AppText variant="label" tone="primary">
            {t('Change photo')}
          </AppText>
        </Pressable>
      </View>

      <SummarySection title={t('Location')} onEdit={() => onEditStep('location')} editLabel={t('Edit location')}>
        <SelectedLocationCard
          place={selectedPlace.place}
          isResolving={selectedPlace.isResolving}
          isUnresolved={selectedPlace.isUnresolved}
        />
      </SummarySection>

      <SummarySection title={t('Details')} onEdit={() => onEditStep('details')} editLabel={t('Edit details')}>
        <AppText variant="body">
          {t('Vehicle type')}: {t(VEHICLE_LABEL_KEYS[draft.vehicleType])}
        </AppText>
        <AppText variant="body">
          {t('Parking type')}: {t(PARKING_LABEL_KEYS[draft.parkingContext])}
        </AppText>
        {draft.note.trim() ? (
          <AppText variant="body" tone="muted">
            {t('Note')}: {draft.note.trim()}
          </AppText>
        ) : (
          <AppText variant="body" tone="muted">
            {t('No note')}
          </AppText>
        )}
      </SummarySection>

      {errorMessage ? (
        <AppText variant="callout" tone="danger" accessibilityRole="alert">
          {errorMessage}
        </AppText>
      ) : null}

      <Button label={t('Share spot')} onPress={onShare} loading={isSharing} disabled={!canShare} />
    </View>
  );
}

function SummarySection({
  title,
  onEdit,
  editLabel,
  children,
}: {
  title: string;
  onEdit: () => void;
  editLabel: string;
  children: ReactNode;
}) {
  const theme = useTheme();
  return (
    <View
      style={[
        styles.sectionCard,
        { backgroundColor: theme.colors.surface, borderColor: theme.colors.border, borderRadius: theme.radius.lg },
      ]}
    >
      <View style={styles.sectionHeader}>
        <AppText variant="subtitle">{title}</AppText>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={editLabel}
          hitSlop={HIT_SLOP}
          onPress={onEdit}
          style={styles.editBtn}
        >
          <AppText variant="label" tone="primary">
            {editLabel}
          </AppText>
        </Pressable>
      </View>
      <View style={styles.sectionBody}>{children}</View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { gap: 16 },
  previewFrame: {
    height: 160,
    borderWidth: 1,
    overflow: 'hidden',
    backgroundColor: '#000',
  },
  previewImage: { width: '100%', height: '100%' },
  changePhoto: {
    position: 'absolute',
    right: 10,
    bottom: 10,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  sectionCard: {
    borderWidth: 1,
    padding: 14,
    gap: 10,
  },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
  },
  editBtn: { paddingVertical: 4, paddingHorizontal: 4 },
  sectionBody: { gap: 6 },
});
