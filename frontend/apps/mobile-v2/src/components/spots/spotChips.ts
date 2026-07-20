import type { MaterialCommunityIcons } from '@expo/vector-icons';
import type { LegalStatus, ParkingContext, PublicSpot, SpotVehicleType } from '@parkio/types';
import type { Translator } from '@/i18n/LocaleProvider';

type IconName = keyof typeof MaterialCommunityIcons.glyphMap;

export interface SpotChipModel {
  key: string;
  icon: IconName;
  label: string;
}

export const VEHICLE_ICONS: Record<SpotVehicleType, IconName> = {
  SEDAN: 'car',
  HATCHBACK: 'car-hatchback',
  SUV: 'car-estate',
  VAN: 'van-utility',
  MOTORCYCLE: 'motorbike',
  ANY: 'car-multiple',
};

export const CONTEXT_ICONS: Record<ParkingContext, IconName> = {
  STREET_PARKING: 'road-variant',
  OPEN_PARKING_LOT: 'parking',
  INDOOR_PARKING: 'garage-variant',
  MALL_PARKING: 'storefront-outline',
  RESIDENTIAL_AREA: 'home-city-outline',
  OFFICE_AREA: 'office-building-outline',
  UNKNOWN: 'help-circle-outline',
};

export const LEGAL_ICONS: Record<LegalStatus, IconName> = {
  LEGAL: 'gavel',
  UNCERTAIN: 'help-circle-outline',
  ILLEGAL_OR_RISKY: 'alert-octagon-outline',
};

/**
 * Attribute chips for a spot (vehicle fit → context → legal), capped by the
 * caller. Vehicle lists collapse: "ANY" or 3+ types read as the ANY label.
 */
export function spotChips(
  spot: Pick<PublicSpot, 'suitableVehicleTypes' | 'parkingContext' | 'legalStatus'>,
  t: Translator,
): SpotChipModel[] {
  const chips: SpotChipModel[] = [];
  const vehicles = spot.suitableVehicleTypes;
  if (vehicles.length > 0) {
    if (vehicles.includes('ANY') || vehicles.length >= 3) {
      chips.push({ key: 'veh', icon: VEHICLE_ICONS.ANY, label: t('vehicle.ANY') });
    } else {
      for (const vehicle of vehicles.slice(0, 2)) {
        chips.push({ key: `veh-${vehicle}`, icon: VEHICLE_ICONS[vehicle], label: t(`vehicle.${vehicle}`) });
      }
    }
  }
  chips.push({
    key: 'ctx',
    icon: CONTEXT_ICONS[spot.parkingContext],
    label: t(`context.${spot.parkingContext}`),
  });
  chips.push({
    key: 'legal',
    icon: LEGAL_ICONS[spot.legalStatus],
    label: t(`legal.${spot.legalStatus}`),
  });
  return chips;
}

/** Short display title: address text, else the context label. */
export function spotTitle(
  spot: Pick<PublicSpot, 'addressText' | 'parkingContext'>,
  t: Translator,
): string {
  const address = spot.addressText?.trim();
  return address && address.length > 0 ? address : t(`context.${spot.parkingContext}`);
}
