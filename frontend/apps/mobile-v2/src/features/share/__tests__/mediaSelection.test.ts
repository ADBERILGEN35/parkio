import {
  beginMediaSelection,
  isLatestMediaSelection,
  resetMediaSelectionGuard,
} from '../mediaSelectionGuard';
import { writeJson, removeJson } from '@/services/jsonStore';
import { useShareDraftStore } from '../state/shareDraftStore';

type PickedAsset = { uri: string; width: number; height: number };
type PickResult =
  | { status: 'picked'; asset: PickedAsset }
  | { status: 'cancelled' }
  | { status: 'error' };

/** Mirrors the share wizard gallery/camera commit path for deterministic tests. */
async function applyShareMediaSelection(
  pick: () => Promise<PickResult>,
  prepare: (asset: PickedAsset) => Promise<PickedAsset>,
): Promise<'applied' | 'cancelled' | 'error' | 'ignored-generation' | 'ignored-selection'> {
  const generation = useShareDraftStore.getState().generation;
  const selection = beginMediaSelection();
  const result = await pick();
  if (!useShareDraftStore.getState().isGenerationCurrent(generation)) {
    return 'ignored-generation';
  }
  if (!isLatestMediaSelection(selection)) {
    return 'ignored-selection';
  }
  if (result.status === 'cancelled') {
    return 'cancelled';
  }
  if (result.status === 'error') {
    return 'error';
  }
  try {
    const prepared = await prepare(result.asset);
    if (!useShareDraftStore.getState().isGenerationCurrent(generation)) {
      return 'ignored-generation';
    }
    if (!isLatestMediaSelection(selection)) {
      return 'ignored-selection';
    }
    useShareDraftStore.getState().setPhoto(prepared);
    return 'applied';
  } catch {
    return 'error';
  }
}

describe('share media selection', () => {
  beforeEach(() => {
    useShareDraftStore.getState().reset();
    resetMediaSelectionGuard();
  });

  it('camera A then gallery B shows B', async () => {
    useShareDraftStore.getState().setPhoto({ uri: 'file://camera-a.jpg', width: 10, height: 10 });
    const outcome = await applyShareMediaSelection(
      async () => ({ status: 'picked', asset: { uri: 'file://gallery-b.jpg', width: 20, height: 20 } }),
      async (asset) => ({ uri: '/doc/parkio-store/draft-photo-2.jpg', width: asset.width, height: asset.height }),
    );
    expect(outcome).toBe('applied');
    expect(useShareDraftStore.getState().photo?.uri).toBe('/doc/parkio-store/draft-photo-2.jpg');
  });

  it('gallery A then camera B shows B', async () => {
    useShareDraftStore.getState().setPhoto({ uri: 'file://gallery-a.jpg', width: 10, height: 10 });
    const outcome = await applyShareMediaSelection(
      async () => ({ status: 'picked', asset: { uri: 'file://camera-b.jpg', width: 30, height: 30 } }),
      async () => ({ uri: '/doc/parkio-store/draft-photo-3.jpg', width: 30, height: 30 }),
    );
    expect(outcome).toBe('applied');
    expect(useShareDraftStore.getState().photo?.uri).toBe('/doc/parkio-store/draft-photo-3.jpg');
  });

  it('camera A then camera B shows B', async () => {
    useShareDraftStore.getState().setPhoto({ uri: 'file://camera-a.jpg', width: 10, height: 10 });
    await applyShareMediaSelection(
      async () => ({ status: 'picked', asset: { uri: 'file://camera-b.jpg', width: 11, height: 11 } }),
      async () => ({ uri: '/doc/parkio-store/draft-photo-4.jpg', width: 11, height: 11 }),
    );
    expect(useShareDraftStore.getState().photo?.uri).toBe('/doc/parkio-store/draft-photo-4.jpg');
  });

  it('gallery A then gallery B shows B', async () => {
    useShareDraftStore.getState().setPhoto({ uri: 'file://gallery-a.jpg', width: 10, height: 10 });
    await applyShareMediaSelection(
      async () => ({ status: 'picked', asset: { uri: 'file://gallery-b.jpg', width: 12, height: 12 } }),
      async () => ({ uri: '/doc/parkio-store/draft-photo-5.jpg', width: 12, height: 12 }),
    );
    expect(useShareDraftStore.getState().photo?.uri).toBe('/doc/parkio-store/draft-photo-5.jpg');
  });

  it('cancelled second picker preserves A', async () => {
    useShareDraftStore.getState().setPhoto({ uri: 'file://keep-a.jpg', width: 10, height: 10, revision: 1 });
    const outcome = await applyShareMediaSelection(async () => ({ status: 'cancelled' }), async (asset) => asset);
    expect(outcome).toBe('cancelled');
    expect(useShareDraftStore.getState().photo?.uri).toBe('file://keep-a.jpg');
  });

  it('failed second picker preserves A', async () => {
    useShareDraftStore.getState().setPhoto({ uri: 'file://keep-a.jpg', width: 10, height: 10, revision: 1 });
    const outcome = await applyShareMediaSelection(
      async () => ({ status: 'picked', asset: { uri: 'file://fail.jpg', width: 1, height: 1 } }),
      async () => {
        throw new Error('prepare failed');
      },
    );
    expect(outcome).toBe('error');
    expect(useShareDraftStore.getState().photo?.uri).toBe('file://keep-a.jpg');
  });

  it('slow result A cannot overwrite newer result B', async () => {
    let resolveA: (value: PickedAsset) => void = () => {};
    const prepareA = new Promise<PickedAsset>((resolve) => {
      resolveA = resolve;
    });

    const generation = useShareDraftStore.getState().generation;
    const selectionA = beginMediaSelection();

    beginMediaSelection();
    useShareDraftStore.getState().setPhoto({ uri: '/doc/parkio-store/draft-photo-b.jpg', width: 2, height: 2 });

    resolveA({ uri: '/doc/parkio-store/draft-photo-a.jpg', width: 1, height: 1 });
    const preparedA = await prepareA;
    if (
      useShareDraftStore.getState().isGenerationCurrent(generation) &&
      isLatestMediaSelection(selectionA)
    ) {
      useShareDraftStore.getState().setPhoto(preparedA);
    }

    expect(useShareDraftStore.getState().photo?.uri).toBe('/doc/parkio-store/draft-photo-b.jpg');
    expect(isLatestMediaSelection(selectionA)).toBe(false);
  });

  it('hydrate does not restore an older photo over a newer in-memory photo', async () => {
    await writeJson('share-draft', {
      step: 'photo',
      photo: { uri: '/doc/parkio-store/draft-photo-old.jpg', width: 10, height: 10, revision: 1 },
      photoRevision: 1,
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
      savedAt: new Date().toISOString(),
    });

    useShareDraftStore.setState({ hydrated: false, photoRevision: 0, photo: null });
    const hydratePromise = useShareDraftStore.getState().hydrate();
    useShareDraftStore.getState().setPhoto({
      uri: '/doc/parkio-store/draft-photo-new.jpg',
      width: 20,
      height: 20,
    });
    await hydratePromise;

    expect(useShareDraftStore.getState().photo?.uri).toBe('/doc/parkio-store/draft-photo-new.jpg');
    expect(useShareDraftStore.getState().photoRevision).toBe(1);
    await removeJson('share-draft');
  });

  it('setPhoto bumps revision and replaces uri atomically', () => {
    const store = useShareDraftStore.getState();
    store.setPhoto({ uri: 'file://first.jpg', width: 1, height: 1 });
    expect(useShareDraftStore.getState().photo?.revision).toBe(1);
    store.setPhoto({ uri: 'file://second.jpg', width: 2, height: 2 });
    const state = useShareDraftStore.getState();
    expect(state.photo?.uri).toBe('file://second.jpg');
    expect(state.photo?.revision).toBe(2);
    expect(state.photoRevision).toBe(2);
  });
});