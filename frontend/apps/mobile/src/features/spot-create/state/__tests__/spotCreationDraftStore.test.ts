import type { UploadMediaResponse } from '@parkio/types';
import { deleteTempFile } from '@/features/media/lib/fileSystem';
import { useSpotCreationDraftStore } from '../spotCreationDraftStore';

jest.mock('@/features/media/lib/fileSystem', () => ({
  deleteTempFile: jest.fn(),
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
});
