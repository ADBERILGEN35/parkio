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
      <button
        type="button"
        onMouseDown={(event) => event.preventDefault()}
        onClick={() => setState('collapsed')}
      >
        collapse-sheet
      </button>
      <BottomSheet
        state={state}
        onStateChange={setState}
        ariaLabel="Results"
        handleAriaLabel={`Results, ${state}. Drag or use arrow keys to resize.`}
      >
        <button type="button">Sheet body content</button>
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
  it('shows the peek footer outside the handle and outside the hidden content', () => {
    render(
      <BottomSheet
        state="collapsed"
        onStateChange={() => {}}
        ariaLabel="Results"
        handleAriaLabel="Results, collapsed."
        peekFooter={<span>Map credits</span>}
      >
        <p>Sheet body</p>
      </BottomSheet>,
    );
    const footer = screen.getByText('Map credits');
    expect(handle()).not.toContainElement(footer);
    expect(footer.closest('[aria-hidden="true"]')).toBeNull();
    expect(footer.closest('[inert]')).toBeNull();
  });

  it('leaves a given top inset free and otherwise uses the whole container', () => {
    const { rerender } = render(
      <BottomSheet state="expanded" onStateChange={() => {}} ariaLabel="Results" handleAriaLabel="Results, expanded.">
        <p>Sheet body</p>
      </BottomSheet>,
    );
    const sheet = screen.getByRole('complementary', { name: 'Results' });
    expect(sheet.style.maxHeight).toBe('');

    rerender(
      <BottomSheet
        state="expanded"
        onStateChange={() => {}}
        ariaLabel="Results"
        handleAriaLabel="Results, expanded."
        topInset={140}
      >
        <p>Sheet body</p>
      </BottomSheet>,
    );
    expect(sheet.style.maxHeight).toBe('calc(100% - 140px)');
  });

  it('keeps its content mounted but hidden from the accessibility tree while collapsed', () => {
    render(<Harness />);
    const content = screen.getByRole('button', { name: 'Sheet body content', hidden: true });
    expect(content).toBeInTheDocument();
    expect(content.parentElement).toHaveAttribute('aria-hidden', 'true');
    expect(content.parentElement).toHaveAttribute('inert', '');
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

  it('returns focus to the handle when collapsing from focused sheet content', async () => {
    const user = userEvent.setup();
    render(<Harness initial="half" />);

    const content = screen.getByRole('button', { name: 'Sheet body content' });
    content.focus();
    expect(content).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'collapse-sheet' }));
    expect(handle()).toHaveFocus();
    expect(screen.getByTestId('state')).toHaveTextContent('collapsed');
  });
});
