import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/utils';
import { OfflineBanner } from './OfflineBanner';

function setOnlineState(value: boolean) {
  Object.defineProperty(window.navigator, 'onLine', {
    configurable: true,
    get: () => value,
  });
}

afterEach(() => {
  setOnlineState(true);
});

describe('OfflineBanner', () => {
  it('stays hidden while the browser is online', () => {
    setOnlineState(true);
    renderWithProviders(<OfflineBanner />);

    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('announces the offline state', () => {
    setOnlineState(false);
    renderWithProviders(<OfflineBanner />);

    expect(screen.getByRole('status')).toHaveTextContent(/you are offline/i);
  });
});
