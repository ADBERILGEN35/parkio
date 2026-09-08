import { useCallback, useState } from 'react';
import { useAuthStore } from '@/auth/store';
import { AuthGateDialog } from './AuthGateDialog';

/**
 * Shared anonymous write/detail gate. Returns true when the caller may proceed.
 */
export function useRequireAuth() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const [open, setOpen] = useState(false);
  const [returnPath, setReturnPath] = useState<string | null>(null);

  const requireAuth = useCallback(
    (resumePath?: string | null) => {
      if (isAuthenticated) return true;
      setReturnPath(resumePath ?? null);
      setOpen(true);
      return false;
    },
    [isAuthenticated],
  );

  const closeGate = useCallback(() => {
    setOpen(false);
    setReturnPath(null);
  }, []);

  const authGate = (
    <AuthGateDialog open={open} onClose={closeGate} returnPath={returnPath} />
  );

  return { requireAuth, authGate, isAuthenticated };
}
