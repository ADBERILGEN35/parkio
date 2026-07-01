import type { CreateSpotRequest } from '@parkio/types';
import { createParkingSpotSchema } from '@parkio/validation';
import { isGpsAccuracyAcceptable } from './locationAccuracy';
import type { SpotCreationDraft } from '../state/spotCreationDraftStore';

export interface BuildCreateSpotResult {
  request?: CreateSpotRequest;
  error?: string;
}

export function buildCreateSpotRequest(draft: SpotCreationDraft | null): BuildCreateSpotResult {
  if (!draft) return { error: 'Upload a photo before creating a spot.' };
  if (!draft.location) return { error: 'GPS location is required.' };
  if (!isGpsAccuracyAcceptable(draft.gpsAccuracyMeters)) {
    return { error: 'GPS accuracy is too low. Refresh location or move the marker after a better fix.' };
  }

  const candidate = {
    mediaId: draft.media.mediaId,
    latitude: draft.location.lat,
    longitude: draft.location.lng,
    addressText: '',
    description: draft.note,
    manualLocationEdited: draft.manualLocationEdited,
    suitableVehicleTypes: [draft.vehicleType],
    parkingContext: draft.parkingContext,
    legalStatus: 'LEGAL',
    violationReasons: [],
  };
  const parsed = createParkingSpotSchema.safeParse(candidate);
  if (!parsed.success) {
    return { error: parsed.error.issues[0]?.message ?? 'Please check the spot details.' };
  }

  return {
    request: {
      ...parsed.data,
      addressText: parsed.data.addressText || undefined,
      description: parsed.data.description || undefined,
    },
  };
}
