import {
  decideShareBack,
  isFirstShareStep,
  previousShareStep,
} from '../shareWizardNavigation';

describe('shareWizardNavigation', () => {
  it('maps later steps to the previous Share step', () => {
    expect(previousShareStep('review')).toBe('details');
    expect(previousShareStep('details')).toBe('location');
    expect(previousShareStep('location')).toBe('photo');
    expect(previousShareStep('photo')).toBeNull();
  });

  it('treats photo as the first wizard step', () => {
    expect(isFirstShareStep('photo')).toBe(true);
    expect(isFirstShareStep('location')).toBe(false);
  });

  it('decides step-back vs confirm-cancel', () => {
    expect(decideShareBack('review')).toEqual({ type: 'step', step: 'details' });
    expect(decideShareBack('details')).toEqual({ type: 'step', step: 'location' });
    expect(decideShareBack('location')).toEqual({ type: 'step', step: 'photo' });
    expect(decideShareBack('photo')).toEqual({ type: 'confirm-cancel' });
  });
});