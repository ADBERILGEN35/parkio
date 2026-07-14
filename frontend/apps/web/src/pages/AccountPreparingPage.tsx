import { UnauthorizedError } from '@parkio/api-client';
import { Button, Icon, SkeletonBlock, Surface } from '@parkio/ui';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { authApi, usersApi } from '@/api';
import { performLogout } from '@/auth/logout';
import { clearPendingProfile, getPendingProfile, hasPendingProfile } from '@/auth/pendingProfile';
import { useAuthStore } from '@/auth/store';

/** Poll /auth/me once per second… */
const RETRY_INTERVAL_MS = 1_000;
/** …for up to this long before offering a manual retry / sign out. */
const READINESS_WINDOW_MS = 12_000;

/** Where the page is in the provisioning → profile-save handoff. */
type Phase = 'provisioning' | 'saving-profile';

/**
 * Post-register holding screen. After registration the backend returns tokens and
 * `status=ACTIVE`, but the user-service profile/status is provisioned asynchronously
 * (via a Kafka `UserRegistered` event), so protected calls can briefly fail with
 * 403 ACCOUNT_NOT_ACTIVE. This page polls `/auth/me` during a short grace window
 * (the store's `provisioning` flag suppresses the global suspended screen for that
 * window only). Once the profile is ready it persists any registration-captured
 * profile fields (display name / phone) via `PATCH /users/me` and forwards to /map.
 * A failed profile save is non-fatal — the account still works and a soft warning
 * is shown.
 */
export function AccountPreparingPage() {
  const { t } = useTranslation(['auth', 'common']);
  const navigate = useNavigate();
  const setUser = useAuthStore((s) => s.setUser);
  const endProvisioning = useAuthStore((s) => s.endProvisioning);
  const [timedOut, setTimedOut] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const [phase, setPhase] = useState<Phase>('provisioning');
  const [profileWarning, setProfileWarning] = useState(false);

  const activeRef = useRef(true);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearTimer = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  };

  const finishWithProfile = useCallback(async () => {
    const pending = getPendingProfile();
    if (!hasPendingProfile(pending)) {
      endProvisioning();
      navigate('/map', { replace: true });
      return;
    }

    setPhase('saving-profile');
    try {
      await usersApi.updateMyProfile({
        displayName: pending.displayName || undefined,
        phoneNumber: pending.phoneNumber || undefined,
      });
      if (!activeRef.current) return;
      clearPendingProfile();
      endProvisioning();
      navigate('/map', { replace: true });
    } catch {
      if (!activeRef.current) return;
      clearPendingProfile();
      endProvisioning();
      setProfileWarning(true);
    }
  }, [navigate, endProvisioning]);

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
        await finishWithProfile();
      } catch (error) {
        if (!activeRef.current) return;
        if (error instanceof UnauthorizedError) return;
        if (Date.now() >= deadline) {
          setTimedOut(true);
          return;
        }
        timerRef.current = setTimeout(() => void attempt(), RETRY_INTERVAL_MS);
      }
    };

    void attempt();
  }, [setUser, finishWithProfile]);

  useEffect(() => {
    runReadiness();
    return () => {
      activeRef.current = false;
      clearTimer();
    };
  }, [runReadiness]);

  const onRetry = () => {
    clearTimer();
    setProfileWarning(false);
    setPhase('provisioning');
    runReadiness();
  };

  const onContinue = () => {
    navigate('/map', { replace: true });
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

  if (profileWarning) {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center gap-lg bg-background px-md py-xl text-on-background">
        <Surface level="card" className="w-full max-w-md p-lg text-center">
          <span className="mx-auto mb-md flex h-16 w-16 items-center justify-center rounded-full bg-tertiary-container/40 text-tertiary">
            <Icon name="info" className="text-[32px] leading-none" />
          </span>
          <h1 className="m-0 text-headline-md text-on-surface">{t('auth:preparing.readyTitle')}</h1>
          <p className="m-0 mt-sm text-body-md text-on-surface-variant">
            {t('auth:preparing.profileWarning')}
          </p>
          <div className="mt-lg flex justify-center">
            <Button onClick={onContinue}>
              {t('auth:preparing.continue')}
              <Icon name="arrow_forward" className="text-[16px] leading-none" />
            </Button>
          </div>
        </Surface>
      </main>
    );
  }

  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-lg bg-background px-md py-xl text-on-background">
      <Surface level="card" className="w-full max-w-md p-lg text-center">
        <span className="mx-auto mb-md flex h-16 w-16 items-center justify-center rounded-full bg-primary-container/50 text-primary">
          {timedOut ? (
            <Icon name="schedule" className="text-[32px] leading-none" />
          ) : (
            <SkeletonBlock className="h-8 w-8" rounded="full" />
          )}
        </span>
        <h1 className="m-0 text-headline-md text-on-surface">{t('auth:preparing.title')}</h1>
        {timedOut ? (
          <p className="m-0 mt-sm text-body-md text-on-surface-variant">{t('auth:preparing.timeout')}</p>
        ) : (
          <p className="m-0 mt-sm text-body-md text-on-surface-variant" role="status">
            {phase === 'saving-profile'
              ? t('auth:preparing.savingProfile')
              : t('auth:preparing.waiting')}
          </p>
        )}
        {timedOut ? (
          <div className="mt-lg flex flex-col items-center justify-center gap-sm sm:flex-row">
            <Button onClick={onRetry} disabled={signingOut}>
              <Icon name="refresh" className="text-[16px] leading-none" />
              {t('auth:preparing.tryAgain')}
            </Button>
            <Button variant="ghost" onClick={onSignOut} disabled={signingOut}>
              <Icon name="logout" className="text-[16px] leading-none" />
              {signingOut ? t('auth:preparing.signingOut') : t('auth:preparing.signOut')}
            </Button>
          </div>
        ) : null}
      </Surface>
    </main>
  );
}
