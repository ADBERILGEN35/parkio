import { StyleSheet } from 'react-native';
import { fireEvent } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { ToastProvider } from '@/providers/ToastProvider';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';
import { ShareSourceSheet } from '../ShareSourceSheet';

function renderSheet(
  props: Partial<React.ComponentProps<typeof ShareSourceSheet>> = {},
) {
  const onPick = jest.fn();
  const onResume = jest.fn();
  const onClose = jest.fn();
  const result = renderWithProviders(
    <ToastProvider>
      <ShareSourceSheet
        visible
        onClose={onClose}
        onPick={onPick}
        onResume={onResume}
        {...props}
      />
    </ToastProvider>,
  );
  return { ...result, onPick, onResume, onClose };
}

describe('ShareSourceSheet', () => {
  beforeEach(() => {
    useShareDraftStore.getState().reset();
  });

  it('invokes camera and gallery actions from the chooser', () => {
    const { getByText, onPick } = renderSheet();
    fireEvent.press(getByText('Kamerayla çek'));
    fireEvent.press(getByText('Galeriden seç'));
    expect(onPick).toHaveBeenCalledWith('camera');
    expect(onPick).toHaveBeenCalledWith('gallery');
  });

  it('keeps camera/gallery buttons inside the sheet panel, not under the backdrop', () => {
    const { getByText, getByTestId, onClose, onPick } = renderSheet();

    const panel = getByTestId('sheet-panel');
    const actions = getByTestId('share-source-actions');
    const backdrop = getByTestId('sheet-backdrop');

    expect(panel).toBeTruthy();
    expect(actions).toBeTruthy();

    // Actions live under the panel, never under the backdrop Pressable.
    let ancestor: unknown = actions.parent;
    let sawPanel = false;
    while (ancestor) {
      const node = ancestor as { props?: { testID?: string }; parent?: unknown };
      if (node.props?.testID === 'sheet-panel') {
        sawPanel = true;
      }
      expect(node.props?.testID).not.toBe('sheet-backdrop');
      ancestor = node.parent;
    }
    expect(sawPanel).toBe(true);

    const backdropStyle = StyleSheet.flatten(backdrop.props.style);
    expect(backdropStyle).toEqual(expect.objectContaining({ flex: 1 }));
    expect((backdropStyle as { position?: string }).position).not.toBe('absolute');

    fireEvent.press(getByText('Kamerayla çek'));
    fireEvent.press(getByText('Galeriden seç'));
    expect(onPick).toHaveBeenCalledWith('camera');
    expect(onPick).toHaveBeenCalledWith('gallery');
    expect(onClose).not.toHaveBeenCalled();
  });

  it('dismisses from the backdrop without firing camera/gallery', () => {
    const { getByTestId, onClose, onPick } = renderSheet();
    fireEvent.press(getByTestId('sheet-backdrop'));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onPick).not.toHaveBeenCalled();
  });

  it('continues a resumable draft', () => {
    useShareDraftStore.setState({
      resumableDraft: true,
      description: 'half done',
      photo: { uri: 'file://draft.jpg', width: 10, height: 10 },
    });

    const { getByText, onResume } = renderSheet();
    fireEvent.press(getByText('Devam et'));
    expect(onResume).toHaveBeenCalledTimes(1);
  });

  it('deletes a draft, clears in-memory state, and shows the chooser again', () => {
    useShareDraftStore.setState({
      resumableDraft: true,
      description: 'half done',
      photo: { uri: 'file://draft.jpg', width: 10, height: 10 },
    });

    const { getByText } = renderSheet();
    fireEvent.press(getByText('Taslağı sil'));
    expect(useShareDraftStore.getState().description).toBe('');
    expect(useShareDraftStore.getState().photo).toBeNull();
    expect(useShareDraftStore.getState().resumableDraft).toBe(false);
    getByText('Kamerayla çek');
    getByText('Galeriden seç');
  });
});
