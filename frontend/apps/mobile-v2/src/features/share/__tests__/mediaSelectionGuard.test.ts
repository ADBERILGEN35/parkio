import {
  beginMediaSelection,
  isLatestMediaSelection,
  resetMediaSelectionGuard,
} from '../mediaSelectionGuard';

describe('mediaSelectionGuard', () => {
  beforeEach(() => {
    resetMediaSelectionGuard();
  });

  it('marks only the newest selection as latest', () => {
    const first = beginMediaSelection();
    expect(isLatestMediaSelection(first)).toBe(true);

    const second = beginMediaSelection();
    expect(isLatestMediaSelection(first)).toBe(false);
    expect(isLatestMediaSelection(second)).toBe(true);
  });
});