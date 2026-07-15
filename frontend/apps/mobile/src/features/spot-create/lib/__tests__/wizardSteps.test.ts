import {
  nextWizardStep,
  prevWizardStep,
  wizardStepNumber,
  WIZARD_DISPLAY_STEPS,
} from '../wizardSteps';

describe('wizardSteps', () => {
  it('orders the display steps as Photo -> Location -> Details -> Review', () => {
    expect(WIZARD_DISPLAY_STEPS).toEqual(['photo', 'location', 'details', 'summary']);
    expect(wizardStepNumber('photo')).toBe(1);
    expect(wizardStepNumber('location')).toBe(2);
    expect(wizardStepNumber('details')).toBe(3);
    expect(wizardStepNumber('summary')).toBe(4);
  });

  it('advances nextWizardStep through the post-upload steps', () => {
    expect(nextWizardStep('location')).toBe('details');
    expect(nextWizardStep('details')).toBe('summary');
    expect(nextWizardStep('summary')).toBeNull();
  });

  it('retreats prevWizardStep through the post-upload steps', () => {
    expect(prevWizardStep('summary')).toBe('details');
    expect(prevWizardStep('details')).toBe('location');
    expect(prevWizardStep('location')).toBeNull();
  });
});
