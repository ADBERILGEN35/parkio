import { BackHandler } from 'react-native';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';
import { useShareSessionStore } from '@/features/share/shareSessionStore';
import { useShareWizardBack } from '@/features/share/useShareWizardBack';

const mockReplace = jest.fn();
const mockDismissAll = jest.fn();
const mockCanDismiss = jest.fn(() => true);
const mockAddListener = jest.fn(() => jest.fn());

jest.mock('expo-router', () => ({
  useRouter: () => ({
    replace: mockReplace,
    dismissAll: mockDismissAll,
    canDismiss: mockCanDismiss,
  }),
  useNavigation: () => ({
    addListener: mockAddListener,
  }),
  useFocusEffect: (effect: () => undefined | (() => void)) => {
    const { useEffect } = require('react');
    useEffect(() => effect(), [effect]);
  },
}));

describe('useShareWizardBack', () => {
  let backHandler: (() => boolean) | null = null;
  let removeSpy: jest.Mock;

  beforeEach(() => {
    useShareDraftStore.getState().reset();
    useShareSessionStore.getState().begin('tab-bar', '/(main)/(tabs)/map');
    mockReplace.mockClear();
    mockDismissAll.mockClear();
    mockAddListener.mockClear();
    backHandler = null;
    removeSpy = jest.fn();
    jest.spyOn(BackHandler, 'addEventListener').mockImplementation((_event, handler) => {
      backHandler = handler as () => boolean;
      return { remove: removeSpy } as ReturnType<typeof BackHandler.addEventListener>;
    });
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('moves review to details to location to photo without navigating', () => {
    useShareDraftStore.getState().setStep('review');
    const { result } = renderHook(() => useShareWizardBack());

    act(() => result.current.handleShareBack());
    expect(useShareDraftStore.getState().step).toBe('details');
    expect(result.current.cancelConfirmVisible).toBe(false);

    act(() => result.current.handleShareBack());
    expect(useShareDraftStore.getState().step).toBe('location');

    act(() => result.current.handleShareBack());
    expect(useShareDraftStore.getState().step).toBe('photo');
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it('opens cancel confirm on first step and Continue preserves state', () => {
    useShareDraftStore.getState().setStep('photo');
    useShareDraftStore.getState().setDescription('keep me');
    useShareDraftStore.getState().setPhoto({ uri: 'file://p.jpg', width: 1, height: 1 });

    const { result } = renderHook(() => useShareWizardBack());

    act(() => result.current.handleShareBack());
    expect(result.current.cancelConfirmVisible).toBe(true);

    act(() => result.current.dismissCancelConfirm());
    expect(result.current.cancelConfirmVisible).toBe(false);
    expect(useShareDraftStore.getState().description).toBe('keep me');
    expect(useShareDraftStore.getState().photo?.uri).toBe('file://p.jpg');
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it('confirmed cancel clears state and returns to origin once', async () => {
    useShareDraftStore.getState().setDescription('gone');
    useShareDraftStore.getState().setStep('photo');
    const { result } = renderHook(() => useShareWizardBack());

    act(() => result.current.handleShareBack());
    await act(async () => {
      await result.current.confirmCancelShare();
    });

    await waitFor(() => {
      expect(useShareDraftStore.getState().description).toBe('');
      expect(mockDismissAll).toHaveBeenCalled();
      expect(mockReplace).toHaveBeenCalledWith('/(main)/(tabs)/map');
    });
    expect(mockReplace).toHaveBeenCalledTimes(1);
  });

  it('registers a single hardware back handler and cleans up', () => {
    const { unmount } = renderHook(() => useShareWizardBack());
    expect(BackHandler.addEventListener).toHaveBeenCalledTimes(1);
    expect(typeof backHandler).toBe('function');
    unmount();
    expect(removeSpy).toHaveBeenCalled();
  });

  it('hardware back uses the same handleShareBack path', () => {
    useShareDraftStore.getState().setStep('details');
    renderHook(() => useShareWizardBack());
    act(() => {
      expect(backHandler?.()).toBe(true);
    });
    expect(useShareDraftStore.getState().step).toBe('location');
  });

  it('repeated back while confirm open dismisses as Continue', () => {
    useShareDraftStore.getState().setStep('photo');
    const { result } = renderHook(() => useShareWizardBack());
    act(() => result.current.handleShareBack());
    expect(result.current.cancelConfirmVisible).toBe(true);
    act(() => result.current.handleShareBack());
    expect(result.current.cancelConfirmVisible).toBe(false);
  });

  it('confirmCancelShare is idempotent while in flight', async () => {
    useShareDraftStore.getState().setStep('photo');
    const { result } = renderHook(() => useShareWizardBack());
    act(() => result.current.handleShareBack());

    await act(async () => {
      const a = result.current.confirmCancelShare();
      const b = result.current.confirmCancelShare();
      await Promise.all([a, b]);
    });

    expect(mockReplace).toHaveBeenCalledTimes(1);
  });
});