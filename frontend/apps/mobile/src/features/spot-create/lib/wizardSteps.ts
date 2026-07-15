/** Steps persisted on the draft after photo upload. */
export type SpotCreationWizardStep = 'location' | 'details' | 'summary';

/** Full 4-step flow including photo capture on the upload screen. */
export type WizardDisplayStep = 'photo' | SpotCreationWizardStep;

export const SPOT_WIZARD_STEPS: readonly SpotCreationWizardStep[] = [
  'location',
  'details',
  'summary',
] as const;

export const WIZARD_DISPLAY_STEPS: readonly WizardDisplayStep[] = [
  'photo',
  'location',
  'details',
  'summary',
] as const;

/** English source labels for `t()` — Photo / Location / Details / Review. */
export const WIZARD_STEP_LABEL_KEYS: Record<WizardDisplayStep, string> = {
  photo: 'Photo',
  location: 'Location',
  details: 'Details',
  summary: 'Review',
};

export function nextWizardStep(step: SpotCreationWizardStep): SpotCreationWizardStep | null {
  const index = SPOT_WIZARD_STEPS.indexOf(step);
  if (index < 0 || index >= SPOT_WIZARD_STEPS.length - 1) return null;
  return SPOT_WIZARD_STEPS[index + 1] ?? null;
}

export function prevWizardStep(step: SpotCreationWizardStep): SpotCreationWizardStep | null {
  const index = SPOT_WIZARD_STEPS.indexOf(step);
  if (index <= 0) return null;
  return SPOT_WIZARD_STEPS[index - 1] ?? null;
}

/** 1-based index in the Photo → Location → Details → Review flow. */
export function wizardStepNumber(step: WizardDisplayStep): number {
  const index = WIZARD_DISPLAY_STEPS.indexOf(step);
  return index < 0 ? 1 : index + 1;
}

export function isSpotCreationWizardStep(value: unknown): value is SpotCreationWizardStep {
  return value === 'location' || value === 'details' || value === 'summary';
}
