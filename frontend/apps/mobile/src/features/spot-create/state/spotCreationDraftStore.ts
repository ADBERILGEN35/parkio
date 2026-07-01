import type { LatLng } from '@parkio/geo';
import type { ParkingContext, SpotVehicleType, UploadMediaResponse } from '@parkio/types';
import { create } from 'zustand';

export interface SpotCreationDraft {
  media: UploadMediaResponse;
  previewUri: string;
  location: LatLng | null;
  gpsAccuracyMeters: number | null;
  manualLocationEdited: boolean;
  vehicleType: SpotVehicleType;
  parkingContext: ParkingContext;
  note: string;
  submitIdempotencyKey: string | null;
}

interface SpotCreationDraftState {
  draft: SpotCreationDraft | null;
  startFromUpload: (media: UploadMediaResponse, previewUri: string) => void;
  patchDraft: (patch: Partial<SpotCreationDraft>) => void;
  clearDraft: () => void;
}

const DEFAULT_DRAFT_FIELDS = {
  location: null,
  gpsAccuracyMeters: null,
  manualLocationEdited: false,
  vehicleType: 'ANY' as SpotVehicleType,
  parkingContext: 'STREET_PARKING' as ParkingContext,
  note: '',
  submitIdempotencyKey: null,
};

export const useSpotCreationDraftStore = create<SpotCreationDraftState>((set) => ({
  draft: null,
  startFromUpload: (media, previewUri) =>
    set((state) => ({
      draft: state.draft
        ? {
            ...state.draft,
            media,
            previewUri,
          }
        : {
            media,
            previewUri,
            ...DEFAULT_DRAFT_FIELDS,
          },
    })),
  patchDraft: (patch) =>
    set((state) => ({
      draft: state.draft ? { ...state.draft, ...patch } : null,
    })),
  clearDraft: () => set({ draft: null }),
}));
