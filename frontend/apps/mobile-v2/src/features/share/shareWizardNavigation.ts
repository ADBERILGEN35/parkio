import { SHARE_STEPS, type ShareStep } from '@/features/share/state/shareDraftStore';

/** Previous wizard step, or `null` when already on the first step (`photo`). */
export function previousShareStep(step: ShareStep): ShareStep | null {
  const index = SHARE_STEPS.indexOf(step);
  if (index <= 0) {
    return null;
  }
  return SHARE_STEPS[index - 1] ?? null;
}

export function isFirstShareStep(step: ShareStep): boolean {
  return SHARE_STEPS.indexOf(step) <= 0;
}

export type ShareBackDecision =
  | { type: 'step'; step: ShareStep }
  | { type: 'confirm-cancel' };

/**
 * Pure back decision for the in-wizard flow. Does not navigate or mutate state.
 * Camera is a nested route outside SHARE_STEPS and is handled separately.
 */
export function decideShareBack(step: ShareStep): ShareBackDecision {
  const previous = previousShareStep(step);
  if (previous) {
    return { type: 'step', step: previous };
  }
  return { type: 'confirm-cancel' };
}
