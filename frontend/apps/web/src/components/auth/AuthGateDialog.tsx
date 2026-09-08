import { Button, cn } from '@parkio/ui';
import {
  useEffect,
  useId,
  useRef,
  type KeyboardEvent as ReactKeyboardEvent,
} from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { createSanitizedLoginReturnSearch } from '@/auth/redirect';

export interface AuthGateDialogProps {
  open: boolean;
  onClose: () => void;
  /** Internal path to resume after login (e.g. facility detail). */
  returnPath?: string | null;
}

/**
 * Centralized anonymous→auth gate using the same dialog chrome as ConfirmDialog.
 * Registration CTA always routes to /register, which truthfully reflects CLOSED.
 */
export function AuthGateDialog({
  open,
  onClose,
  returnPath = null,
}: AuthGateDialogProps) {
  const { t } = useTranslation('auth');
  const titleId = useId();
  const descriptionId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);

  const loginSearch = returnPath
    ? createSanitizedLoginReturnSearch(returnPath)
    : '';
  const loginTo = loginSearch ? `/login${loginSearch}` : '/login';

  useEffect(() => {
    if (!open) return;
    previouslyFocused.current = document.activeElement as HTMLElement | null;
    const frame = window.requestAnimationFrame(() => {
      panelRef.current
        ?.querySelector<HTMLElement>('[data-testid="auth-gate-login"]')
        ?.focus();
    });
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
      onClose();
      return;
    }
    if (event.key !== 'Tab' || !panelRef.current) return;
    const focusable = panelRef.current.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])',
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
      className="fixed inset-0 z-[80] flex items-end justify-center bg-inverse-surface/40 p-md sm:items-center"
      role="presentation"
      data-testid="auth-gate-dialog"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
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
        <h2 id={titleId} className="m-0 text-title-lg text-on-surface">
          {t('authGate.title')}
        </h2>
        <p id={descriptionId} className="m-0 mt-sm text-body-md text-on-surface-variant">
          {t('authGate.body')}
        </p>
        <div className="mt-lg flex flex-col gap-sm">
          <Link
            to={loginTo}
            data-testid="auth-gate-login"
            className="inline-flex w-full items-center justify-center rounded-full bg-primary px-lg py-md text-label-md font-semibold text-on-primary no-underline shadow-md hover:bg-primary/90 focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30"
          >
            {t('authGate.login')}
          </Link>
          <Link
            to="/register"
            data-testid="auth-gate-register-status"
            className="inline-flex w-full items-center justify-center rounded-full px-lg py-md text-label-md font-semibold text-primary no-underline hover:bg-primary/10 focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30"
          >
            {t('authGate.registerStatus')}
          </Link>
          <Button type="button" variant="ghost" className="w-full" onClick={onClose}>
            {t('authGate.dismiss')}
          </Button>
        </div>
      </div>
    </div>
  );
}
