import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';
import { LoginPage } from './LoginPage';
import { MapPage } from './MapPage';
import { RegisterPage } from './RegisterPage';
import { renderWithProviders } from '@/test/utils';

vi.mock('@/components/map/NearbySpotsMap', () => ({
  NearbySpotsMap: () => <div role="img" aria-label="Parking map" />,
}));

function stubGeolocation() {
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: {
      getCurrentPosition: vi.fn((_success, error) =>
        error?.({ code: 1 } as GeolocationPositionError),
      ),
    },
  });
}

describe('page accessibility', () => {
  it('login page has no automated axe violations', async () => {
    const { container } = renderWithProviders(<LoginPage />, { initialEntries: ['/login'] });

    expect(await axe(container)).toHaveNoViolations();
  });

  it('register page has no automated axe violations', async () => {
    const { container } = renderWithProviders(<RegisterPage />, { initialEntries: ['/register'] });

    expect(await axe(container)).toHaveNoViolations();
  });

  it('map page shell has no automated axe violations', async () => {
    stubGeolocation();
    const { container } = renderWithProviders(<MapPage />, { initialEntries: ['/map'] });

    expect(await axe(container)).toHaveNoViolations();
  });
});
