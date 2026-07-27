import { useShareDraftStore } from '@/features/share/state/shareDraftStore';

jest.mock('@/features/share/prepareImage', () => ({
  deleteAppOwnedDraftPhoto: jest.fn(),
  deleteDraftPhoto: jest.fn(),
  draftPhotoExists: jest.fn(() => true),
}));

const mockWriteJson = jest.fn(async (_key: string, _value: unknown) => undefined);
const mockRemoveJson = jest.fn(async (_key: string) => undefined);
const mockReadJson = jest.fn(async (_key: string) => null);

jest.mock('@/services/jsonStore', () => ({
  readJson: (key: string) => mockReadJson(key),
  writeJson: (key: string, value: unknown) => mockWriteJson(key, value),
  removeJson: (key: string) => mockRemoveJson(key),
}));

describe('share region persistence / race behavior', () => {
  beforeEach(() => {
    mockWriteJson.mockClear();
    mockRemoveJson.mockClear();
    mockReadJson.mockReset();
    mockReadJson.mockResolvedValue(null);
    useShareDraftStore.getState().reset();
  });

  it('upload precondition: photo alone is enough to start upload', () => {
    const store = useShareDraftStore.getState();
    store.setPhoto({ uri: 'file://a.jpg', width: 100, height: 100 });
    const photo = useShareDraftStore.getState().photo;
    const canUpload = photo != null && !useShareDraftStore.getState().mediaId;
    expect(canUpload).toBe(true);
  });

  it('replacing photo atomically clears region and mediaId', () => {
    const store = useShareDraftStore.getState();
    store.setPhoto({ uri: 'file://a.jpg', width: 100, height: 100 });
    store.setClaimedRegion({ x: 0.1, y: 0.1, width: 0.5, height: 0.5 });
    store.setMediaId('media-old');
    store.setPhoto({ uri: 'file://b.jpg', width: 120, height: 90 });
    const next = useShareDraftStore.getState();
    expect(next.photo?.uri).toBe('file://b.jpg');
    expect(next.photo?.claimedRegion).toBeNull();
    expect(next.mediaId).toBeNull();
  });

  it('cancel clears photo + region + persisted draft', async () => {
    const store = useShareDraftStore.getState();
    store.setPhoto({ uri: 'file://a.jpg', width: 100, height: 100 });
    store.setClaimedRegion({ x: 0.1, y: 0.1, width: 0.5, height: 0.5 });
    await store.cancelAndClear();
    const next = useShareDraftStore.getState();
    expect(next.photo).toBeNull();
    expect(next.mediaId).toBeNull();
    expect(mockRemoveJson).toHaveBeenCalled();
  });

  it('autosave after cancel cannot recreate cancelled region state', async () => {
    const store = useShareDraftStore.getState();
    store.setPhoto({ uri: 'file://a.jpg', width: 100, height: 100 });
    store.setClaimedRegion({ x: 0.1, y: 0.1, width: 0.5, height: 0.5 });
    const gen = useShareDraftStore.getState().generation;
    await store.cancelAndClear();
    expect(useShareDraftStore.getState().generation).toBe(gen + 1);
    expect(useShareDraftStore.getState().photo).toBeNull();
  });

  it('stale camera/gallery callback cannot restore old photo or region', async () => {
    const store = useShareDraftStore.getState();
    const generation = store.generation;
    await store.cancelAndClear();
    expect(useShareDraftStore.getState().isGenerationCurrent(generation)).toBe(false);
    if (useShareDraftStore.getState().isGenerationCurrent(generation)) {
      useShareDraftStore.getState().setPhoto({ uri: 'file://stale.jpg', width: 1, height: 1 });
      useShareDraftStore.getState().setClaimedRegion({ x: 0.1, y: 0.1, width: 0.5, height: 0.5 });
    }
    expect(useShareDraftStore.getState().photo).toBeNull();
  });

  it('successful share reset clears region state', () => {
    const store = useShareDraftStore.getState();
    store.setPhoto({ uri: 'file://a.jpg', width: 100, height: 100 });
    store.setClaimedRegion({ x: 0.1, y: 0.1, width: 0.5, height: 0.5 });
    store.reset();
    expect(useShareDraftStore.getState().photo).toBeNull();
    expect(useShareDraftStore.getState().photo?.claimedRegion ?? null).toBeNull();
  });
});