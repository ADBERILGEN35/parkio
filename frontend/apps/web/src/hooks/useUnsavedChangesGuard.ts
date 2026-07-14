import { useCallback, useEffect } from 'react';
import { useBlocker, type BlockerFunction } from 'react-router-dom';
import { isAuthEscapePath } from '@/lib/uploadDirty';

export interface UnsavedChangesGuardOptions {
  /** When true, route transitions that leave the current pathname are blocked. */
  when: boolean;
}

/**
 * Router-level navigation protection for dirty forms.
 * Uses React Router data-router `useBlocker` (v6.30+) plus `beforeunload`
 * for refresh/tab-close. Auth escape paths are never blocked.
 */
export function useUnsavedChangesGuard({ when }: UnsavedChangesGuardOptions) {
  const shouldBlock = useCallback<BlockerFunction>(
    ({ currentLocation, nextLocation }) => {
      if (!when) return false;
      if (isAuthEscapePath(nextLocation.pathname)) return false;
      return currentLocation.pathname !== nextLocation.pathname;
    },
    [when],
  );

  const blocker = useBlocker(shouldBlock);

  useEffect(() => {
    if (!when) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      // Chromium requires returnValue to be set; custom text is ignored.
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [when]);

  return blocker;
}
