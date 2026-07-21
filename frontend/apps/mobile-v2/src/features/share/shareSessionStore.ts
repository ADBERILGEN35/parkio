import { create } from 'zustand';
import type { ShareSheetEntry } from '@/features/share/shareSheetStore';

export type ShareOrigin = ShareSheetEntry | 'my-spots';

export type ShareReturnHref =
  | '/(main)/(tabs)/map'
  | '/(main)/(tabs)/my-spots'
  | '/(main)/(tabs)/leaderboard'
  | '/(main)/(tabs)/profile';

interface ShareSessionState {
  /** True while the Share wizard (or its camera sub-route) is the active session. */
  active: boolean;
  origin: ShareOrigin;
  returnTo: ShareReturnHref;
  begin: (origin: ShareOrigin, returnTo?: ShareReturnHref) => void;
  end: () => { origin: ShareOrigin; returnTo: ShareReturnHref };
}

const DEFAULT_RETURN: ShareReturnHref = '/(main)/(tabs)/map';

function defaultReturnFor(origin: ShareOrigin): ShareReturnHref {
  switch (origin) {
    case 'leaderboard-cta':
      return '/(main)/(tabs)/leaderboard';
    case 'my-spots':
      return '/(main)/(tabs)/my-spots';
    case 'map-empty-cta':
    case 'tab-bar':
    case 'unknown':
    default:
      return DEFAULT_RETURN;
  }
}

/**
 * Tracks which entry point opened the current Share session so confirmed
 * cancellation can return to that origin explicitly (not via ambient history).
 */
export const useShareSessionStore = create<ShareSessionState>((set, get) => ({
  active: false,
  origin: 'unknown',
  returnTo: DEFAULT_RETURN,
  begin: (origin, returnTo) => {
    set({
      active: true,
      origin,
      returnTo: returnTo ?? defaultReturnFor(origin),
    });
  },
  end: () => {
    const { origin, returnTo } = get();
    set({ active: false, origin: 'unknown', returnTo: DEFAULT_RETURN });
    return { origin, returnTo };
  },
}));
