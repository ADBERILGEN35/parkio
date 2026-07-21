import { removeJson, writeJson, readJson } from '@/services/jsonStore';
import { deleteDraftPhoto, isAppOwnedDraftPhotoUri } from '@/features/share/prepareImage';
import { useShareSessionStore } from '@/features/share/shareSessionStore';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';

describe('share cancelAndClear / generation guards', () => {
  beforeEach(async () => {
    useShareDraftStore.getState().reset();
    useShareSessionStore.getState().end();
    await removeJson('share-draft');
  });

  it('cancelAndClear clears in-memory state', async () => {
    const store = useShareDraftStore.getState();
    store.setDescription('spot note');
    store.setPhoto({ uri: 'file://app/draft.jpg', width: 10, height: 10 });
    store.setStep('details');
    store.setLegalStatus('LEGAL');

    await store.cancelAndClear();

    const state = useShareDraftStore.getState();
    expect(state.description).toBe('');
    expect(state.photo).toBeNull();
    expect(state.step).toBe('photo');
    expect(state.legalStatus).toBeNull();
    expect(state.resumableDraft).toBe(false);
  });

  it('cancelAndClear clears persisted draft', async () => {
    await writeJson('share-draft', {
      step: 'location',
      photo: { uri: 'file://x.jpg', width: 1, height: 1 },
      mediaId: null,
      location: { latitude: 1, longitude: 2 },
      gpsAccuracy: null,
      manualLocationEdited: false,
      addressText: 'Alsancak',
      description: 'keep me',
      vehicleTypes: [],
      parkingContext: 'STREET_PARKING',
      legalStatus: null,
      violationReasons: [],
      savedAt: new Date().toISOString(),
    });

    await useShareDraftStore.getState().cancelAndClear();
    await new Promise((r) => setTimeout(r, 20));

    expect(await readJson('share-draft')).toBeNull();
  });

  it('cancelAndClear is idempotent and does not throw on missing draft', async () => {
    await useShareDraftStore.getState().cancelAndClear();
    await useShareDraftStore.getState().cancelAndClear();
    expect(useShareDraftStore.getState().step).toBe('photo');
    expect(await readJson('share-draft')).toBeNull();
  });

  it('stale autosave cannot recreate draft after cancellation', async () => {
    const store = useShareDraftStore.getState();
    store.setDescription('racing');
    const genBefore = useShareDraftStore.getState().generation;

    await store.cancelAndClear();
    expect(useShareDraftStore.getState().generation).toBe(genBefore + 1);

    store.setDescription('should-not-stick-as-draft-after-cancel');
    await store.cancelAndClear();
    await new Promise((r) => setTimeout(r, 30));

    expect(await readJson('share-draft')).toBeNull();
    expect(useShareDraftStore.getState().description).toBe('');
  });

  it('late generation-guarded photo apply is ignored after cancel', async () => {
    const store = useShareDraftStore.getState();
    const generation = store.generation;
    await store.cancelAndClear();

    expect(store.isGenerationCurrent(generation)).toBe(false);
    if (useShareDraftStore.getState().isGenerationCurrent(generation)) {
      useShareDraftStore.getState().setPhoto({ uri: 'file://late.jpg', width: 1, height: 1 });
    }
    expect(useShareDraftStore.getState().photo).toBeNull();
  });

  it('isAppOwnedDraftPhotoUri rejects user gallery uris', () => {
    expect(isAppOwnedDraftPhotoUri('content://media/external/images/1')).toBe(false);
    expect(isAppOwnedDraftPhotoUri('file:///storage/emulated/0/DCIM/Camera/x.jpg')).toBe(false);
    expect(isAppOwnedDraftPhotoUri('file:///data/user/0/app/files/parkio-store/draft-photo.jpg')).toBe(true);
  });

  it('deleteDraftPhoto is safe when the file is missing', () => {
    expect(() => deleteDraftPhoto()).not.toThrow();
  });

  it('session store remembers origin return targets', () => {
    useShareSessionStore.getState().begin('leaderboard-cta');
    expect(useShareSessionStore.getState().returnTo).toBe('/(main)/(tabs)/leaderboard');
    useShareSessionStore.getState().begin('map-empty-cta');
    expect(useShareSessionStore.getState().returnTo).toBe('/(main)/(tabs)/map');
    const ended = useShareSessionStore.getState().end();
    expect(ended.returnTo).toBe('/(main)/(tabs)/map');
    expect(useShareSessionStore.getState().active).toBe(false);
  });
});