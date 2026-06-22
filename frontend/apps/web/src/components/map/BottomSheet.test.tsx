import { createEvent, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { BottomSheet, type SheetState } from './BottomSheet';

function Harness({ initial = 'collapsed' as SheetState }) {
  const [state, setState] = useState<SheetState>(initial);
  return (
    <div>
      <span data-testid="state">{state}</span>
      <BottomSheet state={state} onStateChange={setState} ariaLabel="Results">
        <p>Sheet body content</p>
      </BottomSheet>
    </div>
  );
}

/** The drag handle is the button whose accessible name describes the sheet. */
function handle() {
  return screen.getByRole('button', { name: /Results,/ });
}

function dispatchPointer(
  element: HTMLElement,
  type: 'pointerDown' | 'pointerMove' | 'pointerUp',
  clientY: number,
) {
  const event = createEvent[type](element, { pointerId: 1 });
  Object.defineProperty(event, 'clientY', { value: clientY });
  fireEvent(element, event);
}

describe('BottomSheet', () => {
  it('always renders its content (present in DOM regardless of snap state)', () => {
    render(<Harness />);
    expect(screen.getByText('Sheet body content')).toBeInTheDocument();
  });

  it('cycles snap states when the handle is tapped', async () => {
    const user = userEvent.setup();
    render(<Harness initial="collapsed" />);

    expect(screen.getByTestId('state')).toHaveTextContent('collapsed');
    await user.click(handle());
    expect(screen.getByTestId('state')).toHaveTextContent('half');
    await user.click(handle());
    expect(screen.getByTestId('state')).toHaveTextContent('expanded');
    await user.click(handle());
    expect(screen.getByTestId('state')).toHaveTextContent('collapsed');
  });

  it('resizes with the keyboard (arrow keys, Home, End)', async () => {
    const user = userEvent.setup();
    render(<Harness initial="collapsed" />);
    handle().focus();

    await user.keyboard('{ArrowUp}');
    expect(screen.getByTestId('state')).toHaveTextContent('half');
    await user.keyboard('{ArrowUp}');
    expect(screen.getByTestId('state')).toHaveTextContent('expanded');
    // Clamps at the top.
    await user.keyboard('{ArrowUp}');
    expect(screen.getByTestId('state')).toHaveTextContent('expanded');

    await user.keyboard('{ArrowDown}');
    expect(screen.getByTestId('state')).toHaveTextContent('half');

    await user.keyboard('{End}');
    expect(screen.getByTestId('state')).toHaveTextContent('collapsed');
    await user.keyboard('{Home}');
    expect(screen.getByTestId('state')).toHaveTextContent('expanded');
  });

  it('snaps one state per drag gesture', () => {
    render(<Harness initial="collapsed" />);
    const dragHandle = handle();

    dispatchPointer(dragHandle, 'pointerDown', 500);
    dispatchPointer(dragHandle, 'pointerMove', 430);
    dispatchPointer(dragHandle, 'pointerUp', 430);

    expect(screen.getByTestId('state')).toHaveTextContent('half');

    dispatchPointer(dragHandle, 'pointerDown', 430);
    dispatchPointer(dragHandle, 'pointerMove', 500);
    dispatchPointer(dragHandle, 'pointerUp', 500);

    expect(screen.getByTestId('state')).toHaveTextContent('collapsed');
  });

  it('exposes expanded state via aria-expanded', async () => {
    const user = userEvent.setup();
    render(<Harness initial="collapsed" />);
    expect(handle()).toHaveAttribute('aria-expanded', 'false');
    await user.click(handle());
    expect(handle()).toHaveAttribute('aria-expanded', 'true');
  });
});
