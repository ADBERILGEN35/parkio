import { useEffect, useRef, useState } from 'react';
import { ScrollView, StyleSheet } from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { VEHICLE_TYPES, type VehicleType } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Chip } from '@/components/ui/Chip';
import { ScreenHeader } from '@/components/ui/ScreenHeader';
import { TextField } from '@/components/ui/TextField';
import { useT } from '@/i18n/LocaleProvider';
import { describeApiError } from '@/lib/apiErrors';
import { usersApi } from '@/services/api';
import { useToast } from '@/providers/ToastProvider';
import { useTheme } from '@/theme/ThemeProvider';

const VEHICLE_ICONS: Record<VehicleType, React.ComponentProps<typeof Chip>['icon']> = {
  MOTORCYCLE: 'motorbike',
  SMALL_CAR: 'car-hatchback',
  SEDAN: 'car',
  SUV: 'car-estate',
  VAN: 'van-utility',
  TRUCK: 'truck-outline',
};

export default function VehicleScreen() {
  const theme = useTheme();
  const t = useT();
  const toast = useToast();
  const queryClient = useQueryClient();
  const insets = useSafeAreaInsets();
  const { colors } = theme;

  const vehicle = useQuery({ queryKey: ['my-vehicle'], queryFn: () => usersApi.getMyVehicle() });
  const [type, setType] = useState<VehicleType | null>(null);
  const [plate, setPlate] = useState('');
  const hydrated = useRef(false);

  useEffect(() => {
    if (vehicle.data && !hydrated.current) {
      hydrated.current = true;
      setType(vehicle.data.vehicleType);
      setPlate(vehicle.data.plate ?? '');
    }
  }, [vehicle.data]);

  const save = useMutation({
    mutationFn: () =>
      usersApi.upsertMyVehicle({
        vehicleType: type,
        plate: plate.trim() || null,
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(['my-vehicle'], updated);
      toast.show(t('profile.vehicle.saved'), 'success');
    },
    onError: (error) => toast.show(describeApiError(error, t).message, 'error'),
  });

  return (
    <SafeAreaView style={[styles.safe, { backgroundColor: colors.background }]} edges={['top']}>
      <ScreenHeader title={t('profile.vehicle.title')} />
      <ScrollView
        contentContainerStyle={[styles.scroll, { paddingBottom: insets.bottom + 24 }]}
        keyboardShouldPersistTaps="handled"
      >
        <AppText variant="bodySm" color={colors.onSurfaceVariant}>
          {t('profile.vehicle.hint')}
        </AppText>
        <AppText variant="bodySm" color={colors.onSurfaceVariant}>
          {t('profile.vehicle.type')}
        </AppText>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips}>
          {VEHICLE_TYPES.map((candidate) => (
            <Chip
              key={candidate}
              icon={VEHICLE_ICONS[candidate]}
              label={t(`profile.vehicle.${candidate}`)}
              selected={type === candidate}
              onPress={() => setType(type === candidate ? null : candidate)}
            />
          ))}
        </ScrollView>
        <TextField
          label={t('profile.vehicle.plate')}
          placeholder={t('profile.vehicle.platePlaceholder')}
          autoCapitalize="characters"
          value={plate}
          onChangeText={setPlate}
          maxLength={16}
        />
        <Button label={t('common.save')} onPress={() => save.mutate()} loading={save.isPending} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  scroll: { padding: 20, paddingTop: 8, gap: 14 },
  chips: { gap: 8, paddingVertical: 2 },
});
