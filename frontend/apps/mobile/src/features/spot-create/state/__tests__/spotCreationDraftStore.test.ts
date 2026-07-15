import type { UploadMediaResponse } from '@parkio/types';
import { deleteTempFile } from '@/features/media/lib/fileSystem';
import { loadPersistedSpotCreationDraft } from '../../lib/spotCreationDraftPersistence';
import { useSpotCreationDraftStore, type SpotCreationDraft } from '../spotCreationDraftStore';

jest.mock('@/features/media/lib/fileSystem', () => ({
  deleteTempFile: jest.fn(),
  getFileSize: jest.fn(() => 1024),
}));

jest.mock('../../lib/spotCreationDraftPersistence', () => ({
  loadPersistedSpotCreationDraft: jest.fn(async () => null),
  persistSpotCreationDraft: jest.fn(async () => undefined),
  clearPersistedSpotCreationDraft: jest.fn(async () => undefined),
}));

function media(id: string): UploadMediaResponse {
  return { mediaId: id, status: 'READY', contentType: 'image/jpeg', fileSize: 2048 };
}

describe('spotCreationDraftStore preview lifecycle', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useSpotCreationDraftStore.setState({ draft: null });
  });

  it('clearDraft deletes the preview file the draft owns', () => {
    useSpotCreationDraftStore.getState().startFromUpload(media('m1'), 'file:///preview-1.jpg');

    useSpotCreationDraftStore.getState().clearDraft();

    expect(deleteTempFile).toHaveBeenCalledWith('file:///preview-1.jpg');
    expect(useSpotCreationDraftStore.getState().draft).toBeNull();
  });

  it('replacing the draft with a new upload deletes the superseded preview', () => {
    const { startFromUpload } = useSpotCreationDraftStore.getState();
    startFromUpload(media('m1'), 'file:///preview-1.jpg');

    startFromUpload(media('m2'), 'file:///preview-2.jpg');

    expect(deleteTempFile).toHaveBeenCalledWith('file:///preview-1.jpg');
    expect(useSpotCreationDraftStore.getState().draft?.previewUri).toBe('file:///preview-2.jpg');
    expect(useSpotCreationDraftStore.getState().draft?.media.mediaId).toBe('m2');
  });

  it('does not delete the preview when replaced with the same uri', () => {
    const { startFromUpload } = useSpotCreationDraftStore.getState();
    startFromUpload(media('m1'), 'file:///preview-1.jpg');

    startFromUpload(media('m1-retry'), 'file:///preview-1.jpg');

    expect(deleteTempFile).not.toHaveBeenCalled();
  });

  it('sets wizardStep to location on startFromUpload', () => {
    useSpotCreationDraftStore.getState().startFromUpload(media('m1'), 'file:///preview-1.jpg');
    expect(useSpotCreationDraftStore.getState().draft?.wizardStep).toBe('location');
  });
});

describe('spotCreationDraftStore hydration', () => {
  const persistedDraft: SpotCreationDraft = {
    media: media('persisted'),
    previewUri: 'file:///persisted-preview.jpg',
    location: null,
    gpsAccuracyMeters: null,
    manualLocationEdited: false,
    vehicleType: 'ANY',
    parkingContext: 'STREET_PARKING',
    note: '',
    submitIdempotencyKey: null,
  };

  beforeEach(() => {
    jest.clearAllMocks();
    useSpotCreationDraftStore.setState({ draft: null });
  });

  it('restores a persisted draft after process death', async () => {
    jest.mocked(loadPersistedSpotCreationDraft).mockResolvedValue(persistedDraft);

    await useSpotCreationDraftStore.getState().hydrateDraft();

    expect(useSpotCreationDraftStore.getState().draft?.media.mediaId).toBe('persisted');
  });

  it('does not overwrite a draft the user started while hydration was in flight', async () => {
    let resolveLoad: (draft: SpotCreationDraft) => void = () => {};
    jest.mocked(loadPersistedSpotCreationDraft).mockReturnValue(
      new Promise((resolve) => {
        resolveLoad = resolve;
      }),
    );

    const hydration = useSpotCreationDraftStore.getState().hydrateDraft();
    // User starts a fresh upload before the slow keystore read settles.
    useSpotCreationDraftStore.getState().startFromUpload(media('fresh'), 'file:///fresh.jpg');
    resolveLoad(persistedDraft);
    await hydration;

    expect(useSpotCreationDraftStore.getState().draft?.media.mediaId).toBe('fresh');
  });
});
