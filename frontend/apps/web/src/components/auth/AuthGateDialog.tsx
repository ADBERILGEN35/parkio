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
import { useRegistrationMode } from '@/auth/useRegistrationMode';

/** Contextual gate intents — copy varies; chrome stays the same. */
export type AuthGateIntent =
  | 'facilityDetail'
  | 'municipalMore'
  | 'community'
  | 'generic';

export interface AuthGateDialogProps {
  open: boolean;
  onClose: () => void;
  /** Internal path to resume after login (e.g. facility detail). */
  returnPath?: string | null;
  /** Which restricted action opened the gate. */
  intent?: AuthGateIntent;
}

/**
 * Centralized anonymous→auth gate using the same dialog chrome as ConfirmDialog.
 * Secondary CTA reflects registration mode: CLOSED/INVITE → about registration;
 * OPEN → Sign up. Never implies open registration while CLOSED.
 */
export function AuthGateDialog({
  open,
  onClose,
  returnPath = null,
  intent = 'generic',
}: AuthGateDialogProps) {
  const { t } = useTranslation('auth');
  const registrationMode = useRegistrationMode();
  const titleId = useId();
  const descriptionId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);

  const loginSearch = returnPath
    ? createSanitizedLoginReturnSearch(returnPath)
    : '';
  const loginTo = loginSearch ? `/login${loginSearch}` : '/login';

  const titleKey =
    intent === 'facilityDetail'
      ? 'authGate.facilityDetailTitle'
      : intent === 'municipalMore'
        ? 'authGate.municipalMoreTitle'
        : intent === 'community'
          ? 'authGate.communityTitle'
          : 'authGate.title';
  const bodyKey =
    intent === 'facilityDetail'
      ? 'authGate.facilityDetailBody'
      : intent === 'municipalMore'
        ? 'authGate.municipalMoreBody'
        : intent === 'community'
          ? 'authGate.communityBody'
          : 'authGate.body';

  const registrationOpen = registrationMode === 'OPEN';
  const registerLabel = registrationOpen
    ? t('authGate.register')
    : t('authGate.registerAbout');

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
      className="fixed inset-0 z-[80] flex items-end justify-center bg-inverse-surface/30 p-md pb-[max(1rem,env(safe-area-inset-bottom))] sm:items-center"
      role="presentation"
      data-testid="auth-gate-dialog"
      data-auth-gate-intent={intent}
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
          'w-full max-w-sm min-w-0 rounded-2xl border border-outline-variant/25 bg-surface-container-lowest px-md py-md shadow-deep sm:px-lg sm:py-lg',
          'animate-fade-in-up',
        )}
      >
        <h2 id={titleId} className="m-0 text-title-lg font-semibold text-on-surface">
          {t(titleKey)}
        </h2>
        <p id={descriptionId} className="m-0 mt-xs text-body-md text-on-surface-variant">
          {t(bodyKey)}
        </p>
        {!registrationOpen ? (
          <p
            className="m-0 mt-xs text-label-sm text-on-surface-variant/80"
            data-testid="auth-gate-registration-support"
          >
            {t('authGate.registerComingSoon')}
          </p>
        ) : null}
        <div className="mt-md flex flex-col gap-xs sm:mt-lg sm:gap-sm">
          <Link
            to={loginTo}
            data-testid="auth-gate-login"
            className="inline-flex w-full items-center justify-center rounded-full bg-primary px-lg py-md text-label-md font-semibold text-on-primary no-underline shadow-md hover:bg-primary/90 focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30"
          >
            {t('authGate.login')}
          </Link>
          <Link
            to="/register"
            data-testid="auth-gate-register"
            data-registration-mode={registrationMode}
            className="inline-flex w-full items-center justify-center rounded-full px-lg py-sm text-label-md font-medium text-on-surface-variant no-underline hover:bg-on-surface/5 focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30"
          >
            {registerLabel}
          </Link>
          <Button
            type="button"
            variant="ghost"
            className="w-full text-label-sm font-medium text-on-surface-variant/80"
            onClick={onClose}
          >
            {t('authGate.dismiss')}
          </Button>
        </div>
      </div>
    </div>
  );
}
