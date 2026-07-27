import { isValidClaimedRegion } from '@parkio/types';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';

jest.mock('@/features/share/prepareImage', () => ({
  deleteAppOwnedDraftPhoto: jest.fn(),
  deleteDraftPhoto: jest.fn(),
  draftPhotoExists: jest.fn(() => true),
}));

jest.mock('@/services/jsonStore', () => ({
  readJson: jest.fn(async () => null),
  writeJson: jest.fn(async () => undefined),
  removeJson: jest.fn(async () => undefined),
}));

describe('share photo continue gating', () => {
  beforeEach(() => {
    useShareDraftStore.getState().reset();
  });

  it('clears optional claimed region when a new photo is set', () => {
    const store = useShareDraftStore.getState();
    store.setPhoto({ uri: 'file://a.jpg', width: 100, height: 100 });
    store.setClaimedRegion({ x: 0.1, y: 0.1, width: 0.4, height: 0.4 });
    expect(isValidClaimedRegion(useShareDraftStore.getState().photo?.claimedRegion)).toBe(true);

    store.setPhoto({ uri: 'file://b.jpg', width: 100, height: 100 });
    expect(useShareDraftStore.getState().photo?.claimedRegion ?? null).toBeNull();
  });

  it('continue requires photo and not failed upload (region optional)', () => {
    const canContinue = (photo: { uri?: string } | null, uploadPhase: string) =>
      photo !== null && uploadPhase !== 'failed';

    expect(canContinue(null, 'idle')).toBe(false);
    expect(canContinue({ uri: 'file://a.jpg' }, 'failed')).toBe(false);
    expect(canContinue({ uri: 'file://a.jpg' }, 'idle')).toBe(true);
    expect(canContinue({ uri: 'file://a.jpg' }, 'uploading')).toBe(true);
  });
});
