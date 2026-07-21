import { isValidClaimedRegion } from '@parkio/types';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';

jest.mock('@/features/share/prepareImage', () => ({
  deleteDraftPhoto: jest.fn(),
  draftPhotoExists: jest.fn(() => true),
}));

jest.mock('@/services/jsonStore', () => ({
  readJson: jest.fn(async () => null),
  writeJson: jest.fn(async () => undefined),
  removeJson: jest.fn(async () => undefined),
}));

describe('share claimed region gating', () => {
  beforeEach(() => {
    useShareDraftStore.getState().reset();
  });

  it('clears claimed region when a new photo is set', () => {
    const store = useShareDraftStore.getState();
    store.setPhoto({ uri: 'file://a.jpg', width: 100, height: 100 });
    store.setClaimedRegion({ x: 0.1, y: 0.1, width: 0.4, height: 0.4 });
    expect(isValidClaimedRegion(useShareDraftStore.getState().photo?.claimedRegion)).toBe(true);

    store.setPhoto({ uri: 'file://b.jpg', width: 100, height: 100 });
    expect(useShareDraftStore.getState().photo?.claimedRegion ?? null).toBeNull();
    expect(isValidClaimedRegion(useShareDraftStore.getState().photo?.claimedRegion)).toBe(false);
  });

  it('setClaimedRegion stores a valid box and resets upload state', () => {
    const store = useShareDraftStore.getState();
    store.setPhoto({ uri: 'file://a.jpg', width: 200, height: 200 });
    store.setUpload('ready', 1);
    store.setMediaId('media-1');

    store.setClaimedRegion({ x: 0.2, y: 0.2, width: 0.5, height: 0.5 });

    const next = useShareDraftStore.getState();
    expect(isValidClaimedRegion(next.photo?.claimedRegion)).toBe(true);
    expect(next.mediaId).toBeNull();
    expect(next.uploadPhase).toBe('idle');
  });

  it('rejects boxes below the minimum area', () => {
    expect(isValidClaimedRegion({ x: 0, y: 0, width: 0.1, height: 0.1 })).toBe(false);
    expect(isValidClaimedRegion({ x: 0, y: 0, width: 0.3, height: 0.3 })).toBe(true);
  });

  it('continue requires photo + valid region and not failed upload', () => {
    const canContinue = (
      photo: { claimedRegion?: { x: number; y: number; width: number; height: number } | null } | null,
      uploadPhase: string,
    ) => photo !== null && isValidClaimedRegion(photo.claimedRegion) && uploadPhase !== 'failed';

    expect(canContinue(null, 'idle')).toBe(false);
    expect(canContinue({ claimedRegion: null }, 'idle')).toBe(false);
    expect(
      canContinue({ claimedRegion: { x: 0.1, y: 0.1, width: 0.4, height: 0.4 } }, 'failed'),
    ).toBe(false);
    expect(
      canContinue({ claimedRegion: { x: 0.1, y: 0.1, width: 0.4, height: 0.4 } }, 'idle'),
    ).toBe(true);
  });
});