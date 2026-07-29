import { renderHook, act } from '@testing-library/react-native';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';

let uploadPromiseResolve: () => void;
let lastUploadSignal: AbortSignal | undefined;

jest.mock('@/services/api', () => ({
  mediaApi: {
    uploadMedia: jest.fn(
      (_file: unknown, _key: unknown, opts?: { signal?: AbortSignal }) => {
        lastUploadSignal = opts?.signal;
        return new Promise<{ mediaId: string; status: string }>((resolve) => {
          uploadPromiseResolve = () =>
            resolve({ mediaId: 'mid-1', status: 'READY' });
        });
      },
    ),
  },
}));

jest.mock('@/hooks/useOnlineStatus', () => ({
  useOnlineStatus: () => true,
}));

jest.mock('@parkio/api-client', () => ({
  createIdempotencyKey: () => 'idem-test-key',
  isValidClaimedRegion: () => false,
}));

jest.mock('@parkio/types', () => ({
  isValidClaimedRegion: () => false,
}));

import { useDraftUpload } from '@/features/share/useDraftUpload';

beforeEach(() => {
  lastUploadSignal = undefined;
  useShareDraftStore.getState().reset();
});

describe('useDraftUpload abort lifecycle', () => {
  it('aborts in-flight upload on unmount', async () => {
    useShareDraftStore.getState().setPhoto({
      uri: 'file://test/photo.jpg',
      width: 100,
      height: 100,
    });

    const { unmount } = renderHook(() => useDraftUpload());

    await act(async () => {
      await Promise.resolve();
    });

    expect(lastUploadSignal).toBeDefined();
    expect(lastUploadSignal!.aborted).toBe(false);

    unmount();

    expect(lastUploadSignal!.aborted).toBe(true);
  });

  it('does not abort after upload completes successfully', async () => {
    useShareDraftStore.getState().setPhoto({
      uri: 'file://test/photo2.jpg',
      width: 100,
      height: 100,
    });

    const { unmount } = renderHook(() => useDraftUpload());

    await act(async () => {
      await Promise.resolve();
    });

    expect(lastUploadSignal).toBeDefined();

    await act(async () => {
      uploadPromiseResolve();
      await Promise.resolve();
    });

    const signalBeforeUnmount = lastUploadSignal!.aborted;
    unmount();

    expect(signalBeforeUnmount).toBe(false);
    expect(lastUploadSignal!.aborted).toBe(false);
  });

  it('cleanup is idempotent - cancel then unmount does not throw', async () => {
    useShareDraftStore.getState().setPhoto({
      uri: 'file://test/photo3.jpg',
      width: 100,
      height: 100,
    });

    const { result, unmount } = renderHook(() => useDraftUpload());

    await act(async () => {
      await Promise.resolve();
    });

    expect(lastUploadSignal).toBeDefined();

    act(() => {
      result.current.cancel();
    });

    expect(lastUploadSignal!.aborted).toBe(true);

    expect(() => unmount()).not.toThrow();
  });
});