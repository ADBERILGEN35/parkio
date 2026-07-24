import type { ReactNode } from 'react';
import { AppRuntimeContext } from './AppRuntimeContext';
import type { WebAppRuntime } from './runtime';

export function AppRuntimeProvider({
  children,
  runtime,
}: {
  children: ReactNode;
  runtime: WebAppRuntime;
}) {
  return <AppRuntimeContext.Provider value={runtime}>{children}</AppRuntimeContext.Provider>;
}
