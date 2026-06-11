import { Button, Card, PageShell } from '@parkio/ui';
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
    <PageShell title="Account suspended">
      <Card title="Your account is not active">
        <p style={{ marginTop: 0 }}>
          Your account has been suspended. If you believe this is a mistake, please contact
          support.
        </p>
        <Button onClick={onSignOut} disabled={signingOut}>
          {signingOut ? 'Signing out…' : 'Sign out'}
        </Button>
      </Card>
    </PageShell>
  );
}
