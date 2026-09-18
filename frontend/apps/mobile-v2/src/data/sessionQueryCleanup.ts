import type { QueryClient } from '@tanstack/react-query';
import { clearUserSessionQueries } from './sessionQueryCache';

/**
 * Allows signOut (outside React) to await the same private-query teardown that
 * SessionQueryCacheSync performs on identity change.
 */
let registeredClient: QueryClient | null = null;

export function registerSessionQueryClient(client: QueryClient): () => void {
  registeredClient = client;
  return () => {
    if (registeredClient === client) {
      registeredClient = null;
    }
  };
}

export async function runRegisteredSessionQueryCleanup(): Promise<void> {
  if (!registeredClient) {
    return;
  }
  await clearUserSessionQueries(registeredClient);
}
