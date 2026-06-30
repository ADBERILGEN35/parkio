import type { UploadMediaResponse } from '@parkio/types';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { mediaApi } from '@/services/media';
import { prepareImage } from '../../lib/prepareImage';
import { useMediaUpload } from '../useMediaUpload';

jest.mock('@parkio/api-client', () => ({
  createIdempotencyKey: jest.fn(() => 'upload-key-1'),
  isParkioApiError: jest.fn(() => false),
}));

jest.mock('@/services/media', () => ({
  mediaApi: { uploadMedia: jest.fn() },
}));

jest.mock('../../lib/prepareImage', () => ({
  prepareImage: jest.fn(),
}));

jest.mock('../../lib/fileSystem', () => ({
  deleteTempFiles: jest.fn(),
}));

describe('useMediaUpload', () => {
  const response: UploadMediaResponse = {
    mediaId: 'media-1',
    status: 'READY',
    contentType: 'image/jpeg',
    fileSize: 2048,
  };

  beforeEach(() => {
    jest.clearAllMocks();
    jest.mocked(prepareImage).mockResolvedValue({
      uri: 'file:///prepared.jpg',
      width: 1200,
      height: 900,
      fileSize: 2048,
      contentType: 'image/jpeg',
      name: 'parkio-spot-test.jpg',
    });
  });

  it('uploads prepared bytes through the shared mediaApi and reports progress', async () => {
    jest.mocked(mediaApi.uploadMedia).mockImplementation(async (_part, _key, options) => {
      options?.onUploadProgress?.({ loaded: 50, total: 100, bytes: 50, lengthComputable: true });
      return response;
    });

    const { result } = renderHook(() => useMediaUpload());

    act(() => {
      result.current.start({ uri: 'file:///capture.jpg', width: 1200, height: 900, temporary: true });
    });

    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(result.current.progress).toBe(1);
    expect(result.current.response).toEqual(response);
    expect(mediaApi.uploadMedia).toHaveBeenCalledWith(
      { uri: 'file:///prepared.jpg', name: 'parkio-spot-test.jpg', type: 'image/jpeg' },
      'upload-key-1',
      expect.objectContaining({ signal: expect.any(AbortSignal), onUploadProgress: expect.any(Function) }),
    );
  });

  it('retries the same prepared file with the same idempotency key after a network failure', async () => {
    jest.mocked(mediaApi.uploadMedia).mockRejectedValueOnce(new Error('Network request failed')).mockResolvedValueOnce(response);

    const { result } = renderHook(() => useMediaUpload());

    act(() => {
      result.current.start({ uri: 'file:///capture.jpg', width: 1200, height: 900, temporary: true });
    });

    await waitFor(() => expect(result.current.phase).toBe('error'));
    act(() => {
      result.current.retry();
    });

    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(prepareImage).toHaveBeenCalledTimes(1);
    expect(mediaApi.uploadMedia).toHaveBeenNthCalledWith(
      1,
      expect.any(Object),
      'upload-key-1',
      expect.any(Object),
    );
    expect(mediaApi.uploadMedia).toHaveBeenNthCalledWith(
      2,
      expect.any(Object),
      'upload-key-1',
      expect.any(Object),
    );
  });
});
