import { cn } from '@parkio/ui';
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
  type ReactNode,
} from 'react';

/** Snap positions, ordered from least to most content revealed. */
export type SheetState = 'collapsed' | 'half' | 'expanded';

const ORDER: SheetState[] = ['collapsed', 'half', 'expanded'];

/**
 * Always-visible peek (drag handle + summary), in px. Exported so map overlays
 * (e.g. the selected-spot preview) can anchor just above the peek instead of
 * hardcoding a magic offset that drifts when the peek height changes.
 */
export const COLLAPSED_PEEK = 88;
/** Viewport-based revealed height for the half state. */
const HALF_VIEWPORT_FRACTION = 0.48;
/** Drag distance (px) past which a flick advances one snap point. */
const FLICK_THRESHOLD = 44;
/** Pointer travel (px) that counts as a drag rather than a tap. */
const TAP_SLOP = 6;
const SHEET_TRANSITION = 'transform 280ms cubic-bezier(0.32, 0.72, 0, 1)';

/**
 * Honor `prefers-reduced-motion`. The global stylesheet already forces tiny
 * transition durations, but the sheet drives `transform` transitions via inline
 * style, so we resolve the snap transition to `none` up front too — no animated
 * slide for users who opted out. SSR/jsdom safe.
 */
function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

export interface BottomSheetProps {
  state: SheetState;
  onStateChange: (state: SheetState) => void;
  /** Accessible name for the sheet region and its drag handle. */
  ariaLabel: string;
  /** Compact content shown in the always-visible peek (e.g. result count). */
  summary?: ReactNode;
  children: ReactNode;
  className?: string;
}

/**
 * Draggable, keyboard-accessible bottom sheet with three snap points.
 *
 * Performance: the drag updates `transform` imperatively via a ref (no React
 * state per pointer-move), so dragging never re-renders the result list or the
 * map underneath. State only changes once, on release, when we snap.
 *
 * Positioning is `absolute inset-x-0 bottom-0` so it anchors to the bottom of
 * its (relative) map container — which already sits above the mobile tab bar —
 * keeping it clear of the bottom navigation. Safe-area padding is applied to the
 * scroll region.
 */
export function BottomSheet({
  state,
  onStateChange,
  ariaLabel,
  summary,
  children,
  className,
}: BottomSheetProps) {
  const sheetRef = useRef<HTMLDivElement>(null);
  const [height, setHeight] = useState(0);
  const [viewportHeight, setViewportHeight] = useState(() =>
    typeof window !== 'undefined' ? window.innerHeight : 720,
  );
  const draggingRef = useRef(false);
  const movedRef = useRef(false);
  const startYRef = useRef(0);
  const baseTranslateRef = useRef(0);
  // Resolved once per render; reduced-motion users get an instant snap, not a slide.
  const sheetTransition = prefersReducedMotion() ? 'none' : SHEET_TRANSITION;

  useLayoutEffect(() => {
    const node = sheetRef.current;
    if (!node) return;
    const measure = () => setHeight(node.getBoundingClientRect().height);
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const readHeight = () => setViewportHeight(window.visualViewport?.height ?? window.innerHeight);
    readHeight();
    window.addEventListener('resize', readHeight);
    window.visualViewport?.addEventListener('resize', readHeight);
    return () => {
      window.removeEventListener('resize', readHeight);
      window.visualViewport?.removeEventListener('resize', readHeight);
    };
  }, []);

  const offsetFor = useCallback(
    (target: SheetState): number => {
      const h = height || (typeof window !== 'undefined' ? window.innerHeight * 0.85 : 640);
      switch (target) {
        case 'expanded':
          return 0;
        case 'half':
          return Math.max(
            0,
            h - clamp(viewportHeight * HALF_VIEWPORT_FRACTION, COLLAPSED_PEEK + 220, 480),
          );
        case 'collapsed':
        default:
          return Math.max(0, h - COLLAPSED_PEEK);
      }
    },
    [height, viewportHeight],
  );

  const goTo = useCallback(
    (target: SheetState) => {
      if (target !== state) onStateChange(target);
    },
    [state, onStateChange],
  );

  const step = useCallback(
    (direction: 1 | -1) => {
      const index = ORDER.indexOf(state);
      goTo(ORDER[Math.min(ORDER.length - 1, Math.max(0, index + direction))]);
    },
    [state, goTo],
  );

  const onHandleKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    switch (event.key) {
      case 'ArrowUp':
        event.preventDefault();
        step(1);
        break;
      case 'ArrowDown':
        event.preventDefault();
        step(-1);
        break;
      case 'Home':
        event.preventDefault();
        goTo('expanded');
        break;
      case 'End':
        event.preventDefault();
        goTo('collapsed');
        break;
      default:
        break;
    }
  };

  const onHandleClick = () => {
    // Suppress the click that ends a drag; only treat genuine taps as a cycle.
    if (movedRef.current) {
      movedRef.current = false;
      return;
    }
    const index = ORDER.indexOf(state);
    onStateChange(ORDER[(index + 1) % ORDER.length]);
  };

  const onPointerDown = (event: PointerEvent<HTMLButtonElement>) => {
    const node = sheetRef.current;
    if (!node) return;
    draggingRef.current = true;
    movedRef.current = false;
    startYRef.current = event.clientY;
    baseTranslateRef.current = offsetFor(state);
    node.style.transition = 'none';
    event.currentTarget.setPointerCapture?.(event.pointerId);
  };

  const onPointerMove = (event: PointerEvent<HTMLButtonElement>) => {
    if (!draggingRef.current) return;
    const node = sheetRef.current;
    if (!node) return;
    const dy = event.clientY - startYRef.current;
    if (Math.abs(dy) > TAP_SLOP) movedRef.current = true;
    const max = offsetFor('collapsed');
    const next = Math.min(max, Math.max(0, baseTranslateRef.current + dy));
    node.style.transform = `translateY(${next}px)`;
  };

  const onPointerUp = (event: PointerEvent<HTMLButtonElement>) => {
    if (!draggingRef.current) return;
    draggingRef.current = false;
    const node = sheetRef.current;
    if (!node) return;
    const dy = event.clientY - startYRef.current;
    const target = resolveSnap(state, dy);
    node.style.transition = sheetTransition;
    node.style.transform = `translateY(${offsetFor(target)}px)`;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    goTo(target);
  };

  return (
    <aside
      ref={sheetRef}
      aria-label={ariaLabel}
      data-state={state}
      className={cn(
        'pointer-events-auto absolute inset-x-0 bottom-0 z-[1050] flex flex-col overflow-hidden rounded-t-3xl glass-panel shadow-sheet-up',
        className,
      )}
      style={{
        height: 'min(88dvh, 100%)',
        transform: `translateY(${offsetFor(state)}px)`,
        transition: sheetTransition,
      }}
    >
      <button
        type="button"
        aria-label={`${ariaLabel}, ${state}. Drag, or press arrow keys to resize.`}
        aria-expanded={state !== 'collapsed'}
        onClick={onHandleClick}
        onKeyDown={onHandleKeyDown}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        className="flex shrink-0 cursor-grab touch-none flex-col items-stretch gap-xs px-md pb-xs pt-sm text-left active:cursor-grabbing focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
        style={{ touchAction: 'none' }}
      >
        <span aria-hidden className="mx-auto h-1.5 w-12 shrink-0 rounded-full bg-outline-variant" />
        {summary ? <span className="block">{summary}</span> : null}
      </button>

      <div className="flex min-h-0 flex-1 flex-col gap-sm overflow-y-auto px-md pb-safe pt-xs hide-scrollbar">
        {children}
      </div>
    </aside>
  );
}

/** Snap one step in the flick direction, or hold position for a small drag. */
function resolveSnap(current: SheetState, dy: number): SheetState {
  const index = ORDER.indexOf(current);
  if (dy <= -FLICK_THRESHOLD) return ORDER[Math.min(ORDER.length - 1, index + 1)];
  if (dy >= FLICK_THRESHOLD) return ORDER[Math.max(0, index - 1)];
  return current;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
