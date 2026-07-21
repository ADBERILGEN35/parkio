import { create } from 'zustand';

export type ShareSheetEntry =
  | 'tab-bar'
  | 'map-empty-cta'
  | 'leaderboard-cta'
  | 'unknown';

/**
 * Global visibility for the Share source / draft sheet so map empty-state and
 * leaderboard CTAs open the same flow as the center tab Share button.
 */
interface ShareSheetState {
  visible: boolean;
  entry: ShareSheetEntry;
  open: (entry?: ShareSheetEntry) => void;
  close: () => void;
}

export const useShareSheetStore = create<ShareSheetState>((set) => ({
  visible: false,
  entry: 'unknown',
  open: (entry = 'unknown') => {
    console.info(`[ShareSheet] open requested entry=${entry}`);
    set({ visible: true, entry });
  },
  close: () => {
    console.info('[ShareSheet] close requested');
    set({ visible: false });
  },
}));
