import { useCallback, useState } from 'react';
import { useAuthStore } from '@/auth/store';
import { AuthGateDialog, type AuthGateIntent } from './AuthGateDialog';

/**
 * Shared anonymous write/detail gate. Returns true when the caller may proceed.
 */
export function useRequireAuth() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const [open, setOpen] = useState(false);
  const [returnPath, setReturnPath] = useState<string | null>(null);
  const [intent, setIntent] = useState<AuthGateIntent>('generic');

  const requireAuth = useCallback(
    (resumePath?: string | null, gateIntent: AuthGateIntent = 'generic') => {
      if (isAuthenticated) return true;
      setReturnPath(resumePath ?? null);
      setIntent(gateIntent);
      setOpen(true);
      return false;
    },
    [isAuthenticated],
  );

  const closeGate = useCallback(() => {
    setOpen(false);
    setReturnPath(null);
    setIntent('generic');
  }, []);

  const authGate = (
    <AuthGateDialog
      open={open}
      onClose={closeGate}
      returnPath={returnPath}
      intent={intent}
    />
  );

  return { requireAuth, authGate, isAuthenticated };
}
