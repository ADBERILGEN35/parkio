import type { LatLng } from '@parkio/geo';
import type { ParkingContext, SpotVehicleType, UploadMediaResponse } from '@parkio/types';
import { create } from 'zustand';
import { deleteTempFile } from '@/features/media/lib/fileSystem';
import {
  clearPersistedSpotCreationDraft,
  loadPersistedSpotCreationDraft,
  persistSpotCreationDraft,
} from '../lib/spotCreationDraftPersistence';

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
  hydrateDraft: () => Promise<void>;
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
  hydrateDraft: async () => {
    const restored = await loadPersistedSpotCreationDraft();
    if (!restored) {
      return;
    }
    // The keystore read is async: if the user already started a fresh draft
    // while it was in flight, that live draft wins — never overwrite it with
    // the stale persisted one (which the subscriber below has also replaced).
    set((state) => (state.draft ? state : { draft: restored }));
  },
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
      deleteTempFile(state.draft?.previewUri);
      void clearPersistedSpotCreationDraft();
      return { draft: null };
    }),
}));

useSpotCreationDraftStore.subscribe((state, prev) => {
  if (state.draft === prev.draft) return;
  if (state.draft) {
    void persistSpotCreationDraft(state.draft);
  } else {
    void clearPersistedSpotCreationDraft();
  }
});
