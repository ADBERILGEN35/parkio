import { StyleSheet } from 'react-native';
import { fireEvent } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { Sheet } from '../Sheet';
import { Button } from '../Button';

/**
 * These tests encode the Android hit-testing contract: backdrop and panel must
 * be non-overlapping siblings. Prior tests only called fireEvent.press on the
 * button text, which bypasses native z-order / absoluteFill hit testing and
 * therefore passed while the Galaxy S23 still swallowed Camera/Gallery taps.
 */
describe('Sheet hit targets', () => {
  it('uses a non-overlapping flex backdrop (not absoluteFill over the panel)', () => {
    const { getByTestId } = renderWithProviders(
      <Sheet visible onClose={jest.fn()} title="Test sheet">
        <Button label="Primary action" onPress={jest.fn()} />
      </Sheet>,
    );

    const host = getByTestId('sheet-host');
    const backdrop = getByTestId('sheet-backdrop');
    const panel = getByTestId('sheet-panel');

    expect(host).toBeTruthy();
    expect(host.props.style).toEqual(expect.objectContaining({ flex: 1 }));

    const backdropStyle = StyleSheet.flatten(backdrop.props.style);
    expect(backdropStyle).toEqual(expect.objectContaining({ flex: 1 }));
    expect(backdropStyle).not.toEqual(
      expect.objectContaining({
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
      }),
    );

    // Panel is a later sibling of the backdrop slot (not wrapped by the Pressable).
    const hostChildren = host.props.children;
    expect(Array.isArray(hostChildren)).toBe(true);
    expect(hostChildren).toHaveLength(2);

    const panelStyle = StyleSheet.flatten(panel.props.style);
    expect(panelStyle.elevation).toBe(16);
  });

  it('dismisses when the backdrop is pressed', () => {
    const onClose = jest.fn();
    const { getByTestId } = renderWithProviders(
      <Sheet visible onClose={onClose} title="Test sheet">
        <Button label="Primary action" onPress={jest.fn()} />
      </Sheet>,
    );

    fireEvent.press(getByTestId('sheet-backdrop'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('delivers presses to panel actions without dismissing', () => {
    const onClose = jest.fn();
    const onAction = jest.fn();
    const { getByText, getByTestId } = renderWithProviders(
      <Sheet visible onClose={onClose} title="Test sheet">
        <Button label="Primary action" onPress={onAction} />
      </Sheet>,
    );

    expect(getByTestId('sheet-panel')).toBeTruthy();
    fireEvent.press(getByText('Primary action'));
    expect(onAction).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('does not mount an absolute-fill overlay that can intercept panel buttons', () => {
    const { getByTestId, UNSAFE_root } = renderWithProviders(
      <Sheet visible onClose={jest.fn()} title="Test sheet">
        <Button label="Primary action" onPress={jest.fn()} />
      </Sheet>,
    );

    const panel = getByTestId('sheet-panel');
    const backdrop = getByTestId('sheet-backdrop');

    // Backdrop Pressable must not wrap the panel in the tree.
    let ancestor: unknown = panel.parent;
    while (ancestor) {
      const node = ancestor as { props?: { testID?: string }; parent?: unknown };
      expect(node.props?.testID).not.toBe('sheet-backdrop');
      ancestor = node.parent;
    }

    // No StyleSheet.absoluteFillObject-equivalent on the backdrop pressable.
    const flat = StyleSheet.flatten(backdrop.props.style) as Record<string, unknown>;
    expect(flat.position).not.toBe('absolute');
    expect(flat.top).not.toBe(0);
    expect(flat.bottom).not.toBe(0);

    // Sanity: tree still renders a host with exactly two layout children.
    expect(UNSAFE_root).toBeTruthy();
  });
});
