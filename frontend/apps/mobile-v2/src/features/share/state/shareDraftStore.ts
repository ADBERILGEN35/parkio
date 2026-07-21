import { create } from 'zustand';
import type {
  ClaimedRegion,
  LegalStatus,
  ParkingContext,
  SpotVehicleType,
  ViolationReason,
} from '@parkio/types';
import { deleteDraftPhoto, draftPhotoExists } from '@/features/share/prepareImage';
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
  /** Normalized claimed free-space box; required before upload/continue. */
  claimedRegion?: ClaimedRegion | null;
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
  /**
   * Monotonic session generation. Bumped on reset/cancel so late async
   * camera/gallery/persist work cannot revive a cancelled flow.
   */
  generation: number;
  uploadPhase: UploadPhase;
  uploadProgress: number;

  hydrate: () => Promise<void>;
  setStep: (step: ShareStep) => void;
  setPhoto: (photo: DraftPhoto) => void;
  setClaimedRegion: (claimedRegion: ClaimedRegion | null) => void;
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
  /** Clears in-memory + persisted draft and deletes the local draft photo file. */
  reset: () => void;
  /**
   * Confirmed cancel: bump generation, clear memory + disk, delete app-owned
   * draft photo. Safe to call repeatedly.
   */
  cancelAndClear: () => Promise<void>;
  /** True when `generation` still matches the caller's captured value. */
  isGenerationCurrent: (generation: number) => boolean;
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
  const generation = state.generation;
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
  void (async () => {
    if (hasContent(snapshot)) {
      await writeJson(STORE_KEY, snapshot);
    } else {
      await removeJson(STORE_KEY);
    }
    // If the flow was cancelled after this write started, undo persistence.
    if (useShareDraftStore.getState().generation !== generation) {
      await removeJson(STORE_KEY);
    }
  })();
}

function clearInMemory(set: (partial: Partial<ShareDraftState>) => void, generation: number): void {
  set({
    ...EMPTY,
    hydrated: true,
    resumableDraft: false,
    generation,
    uploadPhase: 'idle',
    uploadProgress: 0,
  });
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
    generation: 0,
    uploadPhase: 'idle',
    uploadProgress: 0,

    hydrate: async () => {
      const stored = await readJson<PersistedDraft>(STORE_KEY);
      if (stored && hasContent(stored) && isFresh(stored.savedAt)) {
        let photo = stored.photo;
        let step = stored.step;
        // Missing local image: drop the photo and fall back to the photo step.
        if (photo && !draftPhotoExists(photo.uri)) {
          console.warn('[share] draft photo missing on hydrate; clearing photo');
          photo = null;
          step = 'photo';
        }
        const recovered: PersistedDraft = { ...stored, photo, step };
        if (!hasContent(recovered)) {
          void removeJson(STORE_KEY);
          deleteDraftPhoto();
          set({ hydrated: true });
          return;
        }
        set({
          ...recovered,
          hydrated: true,
          resumableDraft: true,
          uploadPhase: recovered.mediaId && recovered.photo ? 'ready' : 'idle',
          uploadProgress: recovered.mediaId && recovered.photo ? 1 : 0,
          mediaId: recovered.photo ? recovered.mediaId : null,
        });
      } else {
        if (stored) {
          void removeJson(STORE_KEY);
          deleteDraftPhoto();
        }
        set({ hydrated: true });
      }
    },

    setStep: (step) => update({ step }),

    setPhoto: (photo) =>
      update({
        // Always clear region on photo replace — never carry over the previous box.
        photo: { uri: photo.uri, width: photo.width, height: photo.height, claimedRegion: null },
        mediaId: null,
        uploadPhase: 'idle',
        uploadProgress: 0,
      }),

    setClaimedRegion: (claimedRegion) => {
      const current = get().photo;
      if (!current) {
        return;
      }
      update({
        photo: { ...current, claimedRegion },
        mediaId: null,
        uploadPhase: 'idle',
        uploadProgress: 0,
      });
    },

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

    isGenerationCurrent: (generation) => get().generation === generation,

    reset: () => {
      const generation = get().generation + 1;
      clearInMemory(set, generation);
      void removeJson(STORE_KEY);
      deleteDraftPhoto();
    },

    cancelAndClear: async () => {
      const generation = get().generation + 1;
      clearInMemory(set, generation);
      deleteDraftPhoto();
      await removeJson(STORE_KEY);
      // Belt-and-suspenders against a racing autosave write.
      await removeJson(STORE_KEY);
    },
  };
});
