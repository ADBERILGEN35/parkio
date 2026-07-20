import { create } from 'zustand';
import { readJson, writeJson } from '@/services/jsonStore';

/**
 * First-run flow progress. Persisted so onboarding shows exactly once;
 * `hydrated` gates routing decisions until the stored value is known.
 */
interface OnboardingState {
  hydrated: boolean;
  completed: boolean;
  hydrate: () => Promise<void>;
  markCompleted: () => void;
}

const STORE_KEY = 'onboarding';

export const useOnboardingStore = create<OnboardingState>((set) => ({
  hydrated: false,
  completed: false,
  hydrate: async () => {
    const stored = await readJson<{ completed?: boolean }>(STORE_KEY);
    set({ hydrated: true, completed: Boolean(stored?.completed) });
  },
  markCompleted: () => {
    set({ completed: true });
    void writeJson(STORE_KEY, { completed: true });
  },
}));
