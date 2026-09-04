import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { API_BASE, server } from '@/test/server';
import { renderWithProviders } from '@/test/utils';
import { PublicExplorePage } from './PublicExplorePage';

vi.mock('@/config/env', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/config/env')>();
  return {
    ...actual,
    frontendConfig: {
      ...actual.frontendConfig,
      features: { ...actual.frontendConfig.features, publicExplore: true },
    },
  };
});

vi.mock('@/components/explore/PublicExploreMap', () => ({
  PublicExploreMap: ({ facilities, onSelect }: {
    facilities: Array<{ id: string; displayName: string }>;
    onSelect: (id: string) => void;
  }) => (
    <div aria-label="read-only map">
      {facilities.map((facility) => (
        <button key={facility.id} type="button" onClick={() => onSelect(facility.id)}>
          {facility.displayName}
        </button>
      ))}
    </div>
  ),
}));

const facility = {
  id: '00000000-0000-0000-0000-000000000901',
  displayName: 'Konak Katli Otoparki',
  operatorName: 'IZELMAN A.S.',
  facilityType: 'OFF_STREET',
  addressText: 'Konak, Izmir',
  latitude: 38.4237,
  longitude: 27.1428,
  capacityTotal: 100,
  availableSpaces: 42,
  availabilityFreshness: 'LIVE',
  dataUpdatedAt: '2026-09-04T10:00:00Z',
  sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
  attribution: 'Includes public sector information licensed under CC BY 4.0.',
};

describe('PublicExplorePage', () => {
  const getCurrentPosition = vi.fn();

  beforeEach(() => {
    vi.restoreAllMocks();
    getCurrentPosition.mockReset();
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition },
    });
  });

  it('loads only the fixed public list and supports read-only marker selection', async () => {
    const listCalls = vi.fn();
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, ({ request }) => {
        listCalls(new URL(request.url).search);
        return HttpResponse.json([facility]);
      }),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });

    expect(await screen.findByText('Live public beta')).toBeInTheDocument();
    expect(screen.getByText('Read-only')).toBeInTheDocument();
    expect(screen.getByText('No account required')).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: facility.displayName }));
    expect(screen.getByTestId('public-explore-facility-panel')).toHaveTextContent(facility.displayName);
    expect(screen.getByTestId('public-explore-facility-panel')).toHaveTextContent(facility.attribution);
    expect(listCalls).toHaveBeenCalledExactlyOnceWith('');
    expect(getCurrentPosition).not.toHaveBeenCalled();

    for (const name of [
      /save/i, /favourite/i, /create parking/i, /share/i, /verify/i, /claim/i,
      /report/i, /upload/i, /parked car/i, /recommendation/i, /ranking/i,
      /admin/i, /sync/i, /import/i,
    ]) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument();
    }
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login');
  });

  it('never substitutes fixtures when the public API is unavailable', async () => {
    server.use(
      http.get(`${API_BASE}/public/explore/facilities`, () =>
        HttpResponse.json({ code: 'UNAVAILABLE' }, { status: 503 }),
      ),
    );

    renderWithProviders(<PublicExplorePage />, { initialEntries: ['/explore'] });

    expect(await screen.findByText('Parking data is temporarily unavailable.')).toBeInTheDocument();
    expect(screen.queryByText(facility.displayName)).not.toBeInTheDocument();
  });
});
