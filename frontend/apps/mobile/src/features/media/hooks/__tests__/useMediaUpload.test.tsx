import type { UploadMediaResponse } from '@parkio/types';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { mediaApi } from '@/services/media';
import { deleteTempFiles } from '../../lib/fileSystem';
import { prepareImage } from '../../lib/prepareImage';
import type { PreparedImage } from '../../types';
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

  const prepared: PreparedImage = {
    uri: 'file:///prepared.jpg',
    width: 1200,
    height: 900,
    fileSize: 2048,
    contentType: 'image/jpeg',
    name: 'parkio-spot-test.jpg',
  };

  beforeEach(() => {
    jest.clearAllMocks();
    jest.mocked(prepareImage).mockResolvedValue(prepared);
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

  it('cancel mid-upload aborts the request, lands on cancelled, and retry reuses the same key', async () => {
    jest
      .mocked(mediaApi.uploadMedia)
      .mockImplementationOnce(
        (_part, _key, options) =>
          new Promise((_resolve, reject) => {
            options?.signal?.addEventListener?.('abort', () => reject(new Error('canceled')));
          }),
      )
      .mockResolvedValueOnce(response);

    const { result } = renderHook(() => useMediaUpload());

    act(() => {
      result.current.start({ uri: 'file:///capture.jpg', width: 1200, height: 900, temporary: true });
    });
    await waitFor(() => expect(result.current.phase).toBe('uploading'));

    act(() => {
      result.current.cancel();
    });
    await waitFor(() => expect(result.current.phase).toBe('cancelled'));

    act(() => {
      result.current.retry();
    });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    // Prepared bytes are reused (no re-preparation) and the key is unchanged →
    // the backend treats the retry as the same request, never a duplicate.
    expect(prepareImage).toHaveBeenCalledTimes(1);
    expect(mediaApi.uploadMedia).toHaveBeenNthCalledWith(2, expect.any(Object), 'upload-key-1', expect.any(Object));
  });

  it('cancel during preparation lands on cancelled without sending any bytes', async () => {
    let resolvePrepare!: (value: PreparedImage) => void;
    jest.mocked(prepareImage).mockImplementation(
      () =>
        new Promise<PreparedImage>((resolve) => {
          resolvePrepare = resolve;
        }),
    );

    const { result } = renderHook(() => useMediaUpload());

    act(() => {
      result.current.start({ uri: 'file:///capture.jpg', width: 1200, height: 900, temporary: true });
    });
    expect(result.current.phase).toBe('preparing');

    act(() => {
      result.current.cancel();
    });
    await act(async () => {
      resolvePrepare(prepared);
    });

    await waitFor(() => expect(result.current.phase).toBe('cancelled'));
    expect(mediaApi.uploadMedia).not.toHaveBeenCalled();
  });

  it('purges the prepared temp file on success but never the source capture (handed off as preview)', async () => {
    jest.mocked(mediaApi.uploadMedia).mockResolvedValue(response);
    // The hook clears its tracking Set right after passing it in — snapshot the
    // uris at call time instead of inspecting the (now empty) live reference.
    const deleted: string[] = [];
    jest.mocked(deleteTempFiles).mockImplementation((uris) => {
      for (const uri of uris) {
        if (uri) deleted.push(uri);
      }
    });

    const { result, unmount } = renderHook(() => useMediaUpload());

    act(() => {
      result.current.start({ uri: 'file:///capture.jpg', width: 1200, height: 900, temporary: true });
    });
    await waitFor(() => expect(result.current.phase).toBe('success'));
    unmount();

    expect(deleted).toContain('file:///prepared.jpg');
    expect(deleted).not.toContain('file:///capture.jpg');
  });

  it('drops a second start while a run is in flight (single upload, single key)', async () => {
    let resolvePrepare!: (value: PreparedImage) => void;
    jest.mocked(prepareImage).mockImplementation(
      () =>
        new Promise<PreparedImage>((resolve) => {
          resolvePrepare = resolve;
        }),
    );
    jest.mocked(mediaApi.uploadMedia).mockResolvedValue(response);

    const { result } = renderHook(() => useMediaUpload());

    act(() => {
      result.current.start({ uri: 'file:///first.jpg', width: 1200, height: 900, temporary: true });
      result.current.start({ uri: 'file:///second.jpg', width: 1200, height: 900, temporary: true });
    });
    await act(async () => {
      resolvePrepare(prepared);
    });

    await waitFor(() => expect(result.current.phase).toBe('success'));
    expect(prepareImage).toHaveBeenCalledTimes(1);
    expect(prepareImage).toHaveBeenCalledWith(expect.objectContaining({ uri: 'file:///first.jpg' }));
    expect(mediaApi.uploadMedia).toHaveBeenCalledTimes(1);
  });
});
