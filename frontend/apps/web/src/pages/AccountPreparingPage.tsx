import { UnauthorizedError } from '@parkio/api-client';
import { Button, Icon, Surface } from '@parkio/ui';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '@/api';
import { performLogout } from '@/auth/logout';
import { useAuthStore } from '@/auth/store';

/** Poll /auth/me once per second… */
const RETRY_INTERVAL_MS = 1_000;
/** …for up to this long before offering a manual retry / sign out. */
const READINESS_WINDOW_MS = 12_000;

/**
 * Post-register holding screen. After registration the backend returns tokens and
 * `status=ACTIVE`, but the user-service profile/status is provisioned asynchronously
 * (via a Kafka `UserRegistered` event), so protected calls can briefly fail with
 * 403 ACCOUNT_NOT_ACTIVE. This page polls `/auth/me` during a short grace window
 * (the store's `provisioning` flag suppresses the global suspended screen for that
 * window only) and forwards to /map once the profile is ready.
 */
export function AccountPreparingPage() {
  const navigate = useNavigate();
  const setUser = useAuthStore((s) => s.setUser);
  const endProvisioning = useAuthStore((s) => s.endProvisioning);
  const [timedOut, setTimedOut] = useState(false);
  const [signingOut, setSigningOut] = useState(false);

  const activeRef = useRef(true);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearTimer = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  };

  const runReadiness = useCallback(() => {
    activeRef.current = true;
    setTimedOut(false);
    const deadline = Date.now() + READINESS_WINDOW_MS;

    const attempt = async () => {
      if (!activeRef.current) return;
      try {
        const user = await authApi.me();
        if (!activeRef.current) return;
        setUser(user);
        endProvisioning();
        navigate('/map', { replace: true });
      } catch (error) {
        if (!activeRef.current) return;
        // A surfaced 401 means refresh already failed and the session was cleared;
        // the route guard will redirect to /login, so stop polling.
        if (error instanceof UnauthorizedError) return;
        // Everything else during the grace window (notably 403 ACCOUNT_NOT_ACTIVE,
        // also 503/transient startup errors) means the profile is still being
        // provisioned — keep polling until the window closes.
        if (Date.now() >= deadline) {
          setTimedOut(true);
          return;
        }
        timerRef.current = setTimeout(() => void attempt(), RETRY_INTERVAL_MS);
      }
    };

    void attempt();
  }, [navigate, setUser, endProvisioning]);

  useEffect(() => {
    runReadiness();
    return () => {
      activeRef.current = false;
      clearTimer();
    };
  }, [runReadiness]);

  const onRetry = () => {
    clearTimer();
    runReadiness();
  };

  const onSignOut = async () => {
    setSigningOut(true);
    activeRef.current = false;
    clearTimer();
    endProvisioning();
    try {
      await performLogout();
    } finally {
      setSigningOut(false);
    }
  };

  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-lg bg-background px-md py-xl text-on-background">
      <Surface level="card" className="w-full max-w-md p-lg text-center">
        <span className="mx-auto mb-md flex h-16 w-16 items-center justify-center rounded-full bg-primary-container/50 text-primary">
          {timedOut ? (
            <Icon name="schedule" className="text-[32px] leading-none" />
          ) : (
            <span
              aria-hidden
              className="inline-block h-8 w-8 animate-spin rounded-full border-2 border-outline-variant border-t-primary"
            />
          )}
        </span>
        <h1 className="m-0 text-headline-md text-on-surface">Preparing your account</h1>
        {timedOut ? (
          <p className="m-0 mt-sm text-body-md text-on-surface-variant">
            This is taking longer than expected. You can try again in a moment, or sign out and
            sign back in.
          </p>
        ) : (
          <p className="m-0 mt-sm text-body-md text-on-surface-variant" role="status">
            This usually takes a few seconds.
          </p>
        )}
        {timedOut ? (
          <div className="mt-lg flex flex-col items-center justify-center gap-sm sm:flex-row">
            <Button onClick={onRetry} disabled={signingOut}>
              <Icon name="refresh" className="text-[16px] leading-none" />
              Try again
            </Button>
            <Button variant="ghost" onClick={onSignOut} disabled={signingOut}>
              <Icon name="logout" className="text-[16px] leading-none" />
              {signingOut ? 'Signing out…' : 'Sign out'}
            </Button>
          </div>
        ) : null}
      </Surface>
    </main>
  );
}
