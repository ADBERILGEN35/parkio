import { useEffect, useState } from 'react';
import { REGISTRATION_MODES, type RegistrationMode } from '@parkio/types';
import { authApi } from '@/services/api';

/**
 * Authoritative registration mode from GET /auth/registration-mode.
 * Fail-safe bootstrap = CLOSED (never imply signup is open before the server answers).
 */
export function useRegistrationMode(): RegistrationMode {
  const [mode, setMode] = useState<RegistrationMode>('CLOSED');

  useEffect(() => {
    const controller = new AbortController();
    authApi
      .getRegistrationMode(controller.signal)
      .then((response) => {
        setMode(REGISTRATION_MODES.includes(response.mode) ? response.mode : 'CLOSED');
      })
      .catch(() => {
        setMode('CLOSED');
      });
    return () => controller.abort();
  }, []);

  return mode;
}

/** Signup CTAs / register form are allowed for OPEN and INVITE (not CLOSED). */
export function isRegistrationSignupAllowed(mode: RegistrationMode): boolean {
  return mode !== 'CLOSED';
}
