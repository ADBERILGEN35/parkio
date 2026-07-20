import { create } from 'zustand';
import type {
  LegalStatus,
  ParkingContext,
  SpotVehicleType,
  ViolationReason,
} from '@parkio/types';
import { readJson, removeJson, writeJson } from '@/services/jsonStore';

/**
 * Share-flow draft — the wizard's single source of truth, persisted across
 * cold starts (brief §12.5.10: "Yarım kalan paylaşımın var — devam et / sil").
 *
 * Transient upload mechanics (progress %, abort controller) live in the upload
 * hook; only durable results (mediaId, photo file copied into the document
 * dir) are stored here.
 */
export type ShareStep = 'photo' | 'location' | 'details' | 'review';

export const SHARE_STEPS: ShareStep[] = ['photo', 'location', 'details', 'review'];

export type UploadPhase =
  | 'idle'
  | 'preparing'
  | 'uploading'
  | 'scanning'
  | 'ready'
  | 'failed'
  | 'offline';

export interface DraftPhoto {
  /** Local uri of the prepared (resized/compressed) JPEG, inside the app sandbox. */
  uri: string;
  width: number;
  height: number;
}

export interface DraftLocation {
  latitude: number;
  longitude: number;
}

interface PersistedDraft {
  step: ShareStep;
  photo: DraftPhoto | null;
  mediaId: string | null;
  location: DraftLocation | null;
  gpsAccuracy: number | null;
  manualLocationEdited: boolean;
  addressText: string;
  description: string;
  vehicleTypes: SpotVehicleType[];
  parkingContext: ParkingContext;
  legalStatus: LegalStatus | null;
  violationReasons: ViolationReason[];
  savedAt: string;
}

interface ShareDraftState extends Omit<PersistedDraft, 'savedAt'> {
  hydrated: boolean;
  /** True when a persisted draft with real content was found on cold start. */
  resumableDraft: boolean;
  uploadPhase: UploadPhase;
  uploadProgress: number;

  hydrate: () => Promise<void>;
  setStep: (step: ShareStep) => void;
  setPhoto: (photo: DraftPhoto) => void;
  clearPhoto: () => void;
  setUpload: (phase: UploadPhase, progress?: number) => void;
  setMediaId: (mediaId: string | null) => void;
  setLocation: (location: DraftLocation, options?: { manual?: boolean }) => void;
  setGpsAccuracy: (accuracy: number | null) => void;
  setAddressText: (addressText: string) => void;
  setDescription: (description: string) => void;
  toggleVehicleType: (vehicleType: SpotVehicleType) => void;
  setParkingContext: (parkingContext: ParkingContext) => void;
  setLegalStatus: (legalStatus: LegalStatus) => void;
  toggleViolationReason: (reason: ViolationReason) => void;
  dismissResume: () => void;
  reset: () => void;
}

const STORE_KEY = 'share-draft';

const EMPTY: Omit<
  PersistedDraft,
  'savedAt'
> = {
  step: 'photo',
  photo: null,
  mediaId: null,
  location: null,
  gpsAccuracy: null,
  manualLocationEdited: false,
  addressText: '',
  description: '',
  vehicleTypes: [],
  parkingContext: 'STREET_PARKING',
  legalStatus: null,
  violationReasons: [],
};

function hasContent(draft: Pick<PersistedDraft, 'photo' | 'description' | 'addressText'>): boolean {
  return Boolean(draft.photo || draft.description.trim() || draft.addressText.trim());
}

/** Drafts older than 24h are stale — the spot moment is long gone. */
function isFresh(savedAt: string): boolean {
  const saved = Date.parse(savedAt);
  return Number.isFinite(saved) && Date.now() - saved < 24 * 3_600_000;
}

function persist(state: ShareDraftState): void {
  const snapshot: PersistedDraft = {
    step: state.step,
    photo: state.photo,
    mediaId: state.mediaId,
    location: state.location,
    gpsAccuracy: state.gpsAccuracy,
    manualLocationEdited: state.manualLocationEdited,
    addressText: state.addressText,
    description: state.description,
    vehicleTypes: state.vehicleTypes,
    parkingContext: state.parkingContext,
    legalStatus: state.legalStatus,
    violationReasons: state.violationReasons,
    savedAt: new Date().toISOString(),
  };
  if (hasContent(snapshot)) {
    void writeJson(STORE_KEY, snapshot);
  } else {
    void removeJson(STORE_KEY);
  }
}

export const useShareDraftStore = create<ShareDraftState>((set, get) => {
  const update = (partial: Partial<ShareDraftState>) => {
    set(partial);
    persist(get());
  };

  return {
    ...EMPTY,
    hydrated: false,
    resumableDraft: false,
    uploadPhase: 'idle',
    uploadProgress: 0,

    hydrate: async () => {
      const stored = await readJson<PersistedDraft>(STORE_KEY);
      if (stored && hasContent(stored) && isFresh(stored.savedAt)) {
        set({
          ...stored,
          hydrated: true,
          resumableDraft: true,
          // A previously-uploaded photo keeps its mediaId; phase resumes as
          // ready so the wizard doesn't re-upload. Failed/partial uploads
          // restart from idle.
          uploadPhase: stored.mediaId ? 'ready' : 'idle',
          uploadProgress: stored.mediaId ? 1 : 0,
        });
      } else {
        if (stored) {
          void removeJson(STORE_KEY);
        }
        set({ hydrated: true });
      }
    },

    setStep: (step) => update({ step }),

    setPhoto: (photo) =>
      update({ photo, mediaId: null, uploadPhase: 'idle', uploadProgress: 0 }),

    clearPhoto: () =>
      update({ photo: null, mediaId: null, uploadPhase: 'idle', uploadProgress: 0 }),

    setUpload: (uploadPhase, uploadProgress) =>
      set((state) => ({
        uploadPhase,
        uploadProgress: uploadProgress ?? state.uploadProgress,
      })),

    setMediaId: (mediaId) => update({ mediaId }),

    setLocation: (location, options) =>
      update({
        location,
        manualLocationEdited: options?.manual ? true : get().manualLocationEdited,
      }),

    setGpsAccuracy: (gpsAccuracy) => set({ gpsAccuracy }),

    setAddressText: (addressText) => update({ addressText }),

    setDescription: (description) => update({ description }),

    toggleVehicleType: (vehicleType) => {
      const current = get().vehicleTypes;
      // "ANY" is exclusive: picking it clears the rest; picking a concrete
      // type clears "ANY".
      let next: SpotVehicleType[];
      if (vehicleType === 'ANY') {
        next = current.includes('ANY') ? [] : ['ANY'];
      } else {
        const without = current.filter((v) => v !== 'ANY');
        next = without.includes(vehicleType)
          ? without.filter((v) => v !== vehicleType)
          : [...without, vehicleType];
      }
      update({ vehicleTypes: next });
    },

    setParkingContext: (parkingContext) => update({ parkingContext }),

    setLegalStatus: (legalStatus) => update({ legalStatus }),

    toggleViolationReason: (reason) => {
      const current = get().violationReasons;
      update({
        violationReasons: current.includes(reason)
          ? current.filter((r) => r !== reason)
          : [...current, reason],
      });
    },

    dismissResume: () => set({ resumableDraft: false }),

    reset: () => {
      set({
        ...EMPTY,
        hydrated: true,
        resumableDraft: false,
        uploadPhase: 'idle',
        uploadProgress: 0,
      });
      void removeJson(STORE_KEY);
    },
  };
});
