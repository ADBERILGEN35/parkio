import { Pressable, StyleSheet, TextInput, View } from 'react-native';
import { PARKING_CONTEXTS, SPOT_VEHICLE_TYPES, type ParkingContext, type SpotVehicleType } from '@parkio/types';
import { AppText } from '@/components/ui';
import { useSpotCreationDraftStore } from '@/features/spot-create/state/spotCreationDraftStore';
import { useLocale } from '@/i18n/LocaleProvider';
import { MIN_TOUCH_TARGET, useTheme } from '@/theme';

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

/** Wizard step 3: vehicle type, parking type, and optional note. */
export function DetailsStep() {
  const draft = useSpotCreationDraftStore((state) => state.draft);
  const patchDraft = useSpotCreationDraftStore((state) => state.patchDraft);
  const { t } = useLocale();

  if (!draft) return null;

  return (
    <View style={styles.root} testID="wizard-details-step">
      <ChoiceGroup
        title={t('Vehicle type')}
        value={draft.vehicleType}
        options={SPOT_VEHICLE_TYPES}
        labels={VEHICLE_LABEL_KEYS}
        onChange={(vehicleType) => patchDraft({ vehicleType })}
      />

      <ChoiceGroup
        title={t('Parking type')}
        value={draft.parkingContext}
        options={PARKING_CONTEXTS}
        labels={PARKING_LABEL_KEYS}
        onChange={(parkingContext) => patchDraft({ parkingContext })}
      />

      <View style={styles.section}>
        <AppText variant="subtitle">{t('Note')}</AppText>
        <NativeNoteInput value={draft.note} onChange={(note) => patchDraft({ note })} />
      </View>
    </View>
  );
}

function ChoiceGroup<T extends string>({
  title,
  value,
  options,
  labels,
  onChange,
}: {
  title: string;
  value: T;
  options: readonly T[];
  labels: Record<T, string>;
  onChange: (value: T) => void;
}) {
  const theme = useTheme();
  const { t } = useLocale();
  return (
    <View style={styles.section}>
      <AppText variant="subtitle">{title}</AppText>
      <View style={styles.choiceWrap}>
        {options.map((option) => {
          const selected = option === value;
          const label = t(labels[option]);
          return (
            <Pressable
              key={option}
              accessibilityRole="button"
              accessibilityState={{ selected }}
              accessibilityLabel={`${label} ${title}`}
              onPress={() => onChange(option)}
              style={[
                styles.choice,
                {
                  minHeight: MIN_TOUCH_TARGET,
                  borderColor: selected ? theme.colors.primary : theme.colors.border,
                  backgroundColor: selected ? theme.colors.primarySoft : theme.colors.surface,
                },
              ]}
            >
              <AppText variant="label" tone={selected ? 'primary' : 'default'}>
                {label}
              </AppText>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

function NativeNoteInput({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const theme = useTheme();
  const { t } = useLocale();
  return (
    <TextInput
      accessibilityLabel={t('Optional note')}
      value={value}
      onChangeText={onChange}
      placeholder={t('Optional: entrance, landmarks, restrictions')}
      placeholderTextColor={theme.colors.textMuted}
      multiline
      maxLength={1000}
      style={[
        styles.noteInput,
        {
          color: theme.colors.text,
          backgroundColor: theme.colors.surface,
          borderColor: theme.colors.border,
        },
      ]}
    />
  );
}

const styles = StyleSheet.create({
  root: { gap: 16 },
  section: { gap: 10 },
  choiceWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  choice: {
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  noteInput: {
    minHeight: 96,
    borderWidth: 1,
    borderRadius: 8,
    padding: 12,
    fontSize: 15,
    textAlignVertical: 'top',
  },
});
