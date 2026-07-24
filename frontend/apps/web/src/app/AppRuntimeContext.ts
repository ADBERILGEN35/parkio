import { createContext, useContext } from 'react';
import type { WebAppRuntime } from './runtime';
import type { ParkioSdk } from './sdk';

export const AppRuntimeContext = createContext<WebAppRuntime | null>(null);

export function useAppRuntime(): WebAppRuntime {
  const runtime = useContext(AppRuntimeContext);
  if (!runtime) {
    throw new Error('AppRuntimeProvider is required before accessing application dependencies.');
  }
  return runtime;
}

export function useParkioSdk(): ParkioSdk {
  return useAppRuntime().sdk;
}
