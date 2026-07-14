import { Button, cn } from '@parkio/ui';
import {
  useEffect,
  useId,
  useRef,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
} from 'react';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description: ReactNode;
  /** Safe / stay action — visual primary, initial focus. */
  cancelLabel: string;
  /** Destructive / leave action. */
  confirmLabel: string;
  confirmVariant?: 'destructive' | 'destructive-soft';
  onCancel: () => void;
  onConfirm: () => void;
}

/**
 * Accessible confirmation dialog for navigation guards and similar flows.
 * Escape / backdrop keep the user in place (safe cancel). Focus is trapped
 * while open; initial focus prefers the safe action.
 */
export function ConfirmDialog({
  open,
  title,
  description,
  cancelLabel,
  confirmLabel,
  confirmVariant = 'destructive-soft',
  onCancel,
  onConfirm,
}: ConfirmDialogProps) {
  const titleId = useId();
  const descriptionId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) return;
    previouslyFocused.current = document.activeElement as HTMLElement | null;
    const frame = window.requestAnimationFrame(() => cancelRef.current?.focus());
    const { overflow } = document.body.style;
    document.body.style.overflow = 'hidden';
    return () => {
      window.cancelAnimationFrame(frame);
      document.body.style.overflow = overflow;
      previouslyFocused.current?.focus?.();
    };
  }, [open]);

  if (!open) return null;

  const onKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      onCancel();
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
      className="fixed inset-0 z-[80] flex items-end justify-center bg-inverse-surface/40 p-md pb-[calc(var(--parkio-mobile-nav-offset,4rem)+0.5rem)] sm:items-center sm:pb-md"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onCancel();
      }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        tabIndex={-1}
        onKeyDown={onKeyDown}
        className={cn(
          'w-full max-w-md min-w-0 rounded-2xl border border-outline-variant/30 bg-surface-container-lowest p-lg shadow-deep',
          'animate-fade-in-up',
        )}
      >
        <h2 id={titleId} className="m-0 break-words text-headline-sm text-on-surface">
          {title}
        </h2>
        <div id={descriptionId} className="mt-sm break-words text-body-md text-on-surface-variant">
          {description}
        </div>
        <div className="mt-lg flex flex-col gap-sm sm:flex-row sm:justify-end">
          <Button
            ref={cancelRef}
            type="button"
            variant="primary"
            className="w-full sm:w-auto"
            onClick={onCancel}
          >
            {cancelLabel}
          </Button>
          <Button
            type="button"
            variant={confirmVariant}
            className="w-full sm:w-auto"
            onClick={onConfirm}
          >
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
