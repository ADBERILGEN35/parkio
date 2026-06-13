import { Button, Icon, Surface } from '@parkio/ui';
import { useState } from 'react';
import { performLogout } from '@/auth/logout';

/**
 * Shown (outside the router) whenever the backend reports
 * 403 ACCOUNT_NOT_ACTIVE. No retries — only sign-out is available.
 */
export function AccountSuspendedPage() {
  const [signingOut, setSigningOut] = useState(false);

  const onSignOut = async () => {
    setSigningOut(true);
    try {
      await performLogout();
    } finally {
      setSigningOut(false);
    }
  };

  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-lg bg-background px-md py-xl text-on-background">
      <Surface level="card" className="w-full max-w-md p-lg text-center">
        <span className="mx-auto mb-md flex h-16 w-16 items-center justify-center rounded-full bg-error-container/50 text-error">
          <Icon name="block" className="text-[32px] leading-none" />
        </span>
        <h1 className="m-0 text-headline-md text-on-surface">Your account is not active</h1>
        <p className="m-0 mt-sm text-body-md text-on-surface-variant">
          Your account has been suspended, so Parkio is unavailable right now. If you believe
          this is a mistake, please contact support with your account email and we'll take a
          look.
        </p>
        <div className="mt-lg flex justify-center">
          <Button onClick={onSignOut} disabled={signingOut}>
            <Icon name="logout" className="text-[16px] leading-none" />
            {signingOut ? 'Signing out…' : 'Sign out'}
          </Button>
        </div>
      </Surface>
    </main>
  );
}
