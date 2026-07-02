import type { LatLng } from '@parkio/geo';
import type { ParkingContext, SpotVehicleType, UploadMediaResponse } from '@parkio/types';
import { create } from 'zustand';
import { deleteTempFile } from '@/features/media/lib/fileSystem';

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
    set((state) => {
      // The draft owns its preview file (handed off by the upload flow). When a
      // new upload replaces the draft, the superseded preview would otherwise
      // be orphaned in the cache — delete it here, best-effort.
      if (state.draft && state.draft.previewUri !== previewUri) {
        deleteTempFile(state.draft.previewUri);
      }
      return {
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
      };
    }),
  patchDraft: (patch) =>
    set((state) => ({
      draft: state.draft ? { ...state.draft, ...patch } : null,
    })),
  clearDraft: () =>
    set((state) => {
      // Terminal for the flow (submitted or abandoned) — the preview file is
      // no longer reachable by any screen, so clean it up.
      deleteTempFile(state.draft?.previewUri);
      return { draft: null };
    }),
}));
