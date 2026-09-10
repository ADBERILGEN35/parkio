import { REGISTRATION_MODES, type RegistrationMode } from '@parkio/types';
import { useEffect, useState } from 'react';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { frontendConfig } from '@/config/env';

/** Runtime mode is authoritative; failures retain the fail-safe CLOSED state. */
export function useRegistrationMode(): RegistrationMode {
  const { authApi } = useParkioSdk();
  const [mode, setMode] = useState<RegistrationMode>(frontendConfig.registrationModeBootstrap);

  useEffect(() => {
    const controller = new AbortController();
    authApi.getRegistrationMode(controller.signal)
      .then((response) => setMode(
        REGISTRATION_MODES.includes(response.mode) ? response.mode : 'CLOSED',
      ))
      .catch(() => setMode('CLOSED'));
    return () => controller.abort();
  }, [authApi]);

  return mode;
}
