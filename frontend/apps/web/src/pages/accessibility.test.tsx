import type { PublicSpot } from '@parkio/types';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';
import { LoginPage } from './LoginPage';
import { MapPage } from './MapPage';
import { RegisterPage } from './RegisterPage';
import { SelectedSpotPreview } from '@/components/map/SelectedSpotPreview';
import type { SpotWithDistance } from '@/lib/spotDiscovery';
import { renderWithProviders } from '@/test/utils';

vi.mock('@/components/map/NearbySpotsMap', () => ({
  NearbySpotsMap: () => <div role="img" aria-label="Parking map" />,
}));

const previewSpot: SpotWithDistance = {
  id: '0b8f6c3a-0000-0000-0000-000000000010',
  mediaId: '0b8f6c3a-0000-0000-0000-000000000011',
  latitude: 41.51,
  longitude: 29.51,
  addressText: 'Stub Address 7',
  description: null,
  manualLocationEdited: false,
  suitableVehicleTypes: ['SEDAN'],
  parkingContext: 'STREET_PARKING',
  legalStatus: 'LEGAL',
  violationReasons: [],
  status: 'ACTIVE',
  expiresAt: '2026-06-11T12:00:00Z',
  createdAt: '2026-06-11T09:00:00Z',
  updatedAt: '2026-06-11T09:00:00Z',
  distanceMeters: 320,
} satisfies PublicSpot & { distanceMeters: number };

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

  it('selected-spot preview has no automated axe violations', async () => {
    const { container } = renderWithProviders(
      <SelectedSpotPreview spot={previewSpot} onClose={vi.fn()} />,
      { initialEntries: ['/map'] },
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it('selected-spot preview exposes a labelled close control reachable by keyboard', async () => {
    const onClose = vi.fn();
    renderWithProviders(<SelectedSpotPreview spot={previewSpot} onClose={onClose} />, {
      initialEntries: ['/map'],
    });
    const user = userEvent.setup();

    // The icon-only close affordance must carry an accessible name (not just a glyph)…
    const close = screen.getByRole('button', { name: 'Close preview' });
    // …and be operable from the keyboard, not pointer-only.
    close.focus();
    expect(close).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('selected-spot preview links to the full detail page with a descriptive name', () => {
    renderWithProviders(<SelectedSpotPreview spot={previewSpot} onClose={vi.fn()} />, {
      initialEntries: ['/map'],
    });

    expect(screen.getByRole('link', { name: /view spot details/i })).toHaveAttribute(
      'href',
      `/spots/${previewSpot.id}`,
    );
  });
});
