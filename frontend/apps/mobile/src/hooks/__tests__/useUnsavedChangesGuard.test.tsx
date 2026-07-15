import { act, renderHook } from '@testing-library/react-native';
import { Alert } from 'react-native';
import { useUnsavedChangesGuard } from '../useUnsavedChangesGuard';

const mockDispatch = jest.fn();
let mockBeforeRemove: ((event: { preventDefault: jest.Mock; data: { action: object } }) => void) | undefined;

jest.mock('expo-router', () => ({
  useNavigation: () => ({
    addListener: (_name: string, listener: typeof mockBeforeRemove) => {
      mockBeforeRemove = listener;
      return jest.fn();
    },
    dispatch: mockDispatch,
  }),
}));

describe('useUnsavedChangesGuard', () => {
  beforeEach(() => {
    mockDispatch.mockReset();
    mockBeforeRemove = undefined;
    jest.spyOn(Alert, 'alert').mockImplementation(jest.fn());
  });

  afterEach(() => jest.restoreAllMocks());

  it('blocks removal while dirty and dispatches only after Leave', () => {
    renderHook(() => useUnsavedChangesGuard(true));
    const event = { preventDefault: jest.fn(), data: { action: { type: 'GO_BACK' } } };

    act(() => mockBeforeRemove?.(event));

    expect(event.preventDefault).toHaveBeenCalledTimes(1);
    expect(mockDispatch).not.toHaveBeenCalled();
    const buttons = jest.mocked(Alert.alert).mock.calls[0]?.[2];
    act(() => buttons?.[1]?.onPress?.());
    expect(mockDispatch).toHaveBeenCalledWith(event.data.action);
  });

  it('does not block after intentional navigation is allowed', () => {
    const { result } = renderHook(() => useUnsavedChangesGuard(true));
    const event = { preventDefault: jest.fn(), data: { action: { type: 'REPLACE' } } };

    act(() => result.current());
    act(() => mockBeforeRemove?.(event));

    expect(event.preventDefault).not.toHaveBeenCalled();
  });
});
