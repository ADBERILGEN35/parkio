import {
  useEffect,
  useId,
  useRef,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
  type RefObject,
} from 'react';

/**
 * Inline confirmation panel with dialog semantics for map chrome.
 * Focus trap, Escape → onDismiss, initial focus, restore previous focus.
 */
export function InlineConfirmPanel({
  title,
  labelledBy,
  describedBy,
  onDismiss,
  dismissDisabled = false,
  initialFocusRef,
  children,
  className,
  testId,
}: {
  title?: string;
  labelledBy?: string;
  describedBy?: string;
  onDismiss: () => void;
  dismissDisabled?: boolean;
  initialFocusRef?: RefObject<HTMLElement | null>;
  children: ReactNode;
  className?: string;
  testId?: string;
}) {
  const autoTitleId = useId();
  const titleId = labelledBy ?? (title ? autoTitleId : undefined);
  const panelRef = useRef<HTMLDivElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);

  useEffect(() => {
    previouslyFocused.current = document.activeElement as HTMLElement | null;
    const frame = window.requestAnimationFrame(() => {
      const preferred = initialFocusRef?.current;
      if (preferred) {
        preferred.focus();
        return;
      }
      const first = panelRef.current?.querySelector<HTMLElement>(
        'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      );
      first?.focus();
    });
    return () => {
      window.cancelAnimationFrame(frame);
      previouslyFocused.current?.focus?.();
    };
  }, [initialFocusRef]);

  const onKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      if (!dismissDisabled) onDismiss();
      return;
    }
    if (event.key !== 'Tab' || !panelRef.current) return;
    const focusable = panelRef.current.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    if (focusable.length === 0) return;
    const first = focusable[0]!;
    const last = focusable[focusable.length - 1]!;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return (
    <div
      ref={panelRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={describedBy}
      tabIndex={-1}
      onKeyDown={onKeyDown}
      data-testid={testId}
      className={className}
    >
      {title ? (
        <p id={titleId} className="m-0 text-label-md font-semibold text-on-surface">
          {title}
        </p>
      ) : null}
      {children}
    </div>
  );
}