import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Destination } from '@parkio/types';
import type { ReactNode } from 'react';

vi.mock('@/data/hooks/useQuickActionSources', () => ({
  useQuickActionSources: () => ({
    snapshot: {
      savedPlaces: [
        {
          id: 'h1',
          kind: 'HOME',
          label: 'Evim',
          latitude: 38.42,
          longitude: 27.13,
          source: 'SYSTEM',
          createdAt: '2026-01-01T00:00:00Z',
          updatedAt: '2026-01-01T00:00:00Z',
        },
      ],
      savedPlacesStatus: 'success',
      favouriteDestinations: [],
      favouriteDestinationsStatus: 'success',
      favouriteParkingCount: 0,
      favouriteParkingStatus: 'success',
      recentDestinations: [],
      recentDestinationsStatus: 'success',
      parkedCarAvailable: false,
      parkedCarStatus: 'success',
    },
    descriptors: [
      { kind: 'HOME', availability: 'AVAILABLE' },
      { kind: 'WORK', availability: 'UNCONFIGURED' },
      { kind: 'FAVOURITE_DESTINATIONS', availability: 'EMPTY', count: 0 },
      { kind: 'FAVOURITE_PARKING', availability: 'EMPTY', count: 0 },
      { kind: 'RECENT_DESTINATIONS', availability: 'EMPTY', count: 0 },
    ],
    saved: { data: [] },
    favouriteDestinations: { data: [] },
    favouriteParking: { data: [] },
    recentDestinations: { data: [] },
    activeSession: { data: null },
    parkedCarAvailable: false,
  }),
}));

vi.mock('@/app/AppRuntimeContext', () => ({
  useParkioSdk: () => ({ parkingApi: {} }),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

import { QuickActionsBar } from './QuickActionsBar';

function wrap(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe('QuickActionsBar', () => {
  it('renders nothing when assistant disabled', () => {
    const { container } = wrap(
      <QuickActionsBar
        enabled={false}
        authenticated
        onSelectDestination={vi.fn()}
        onOpenSearch={vi.fn()}
        onParkedCar={vi.fn()}
        onSelectFavouriteParking={vi.fn()}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('selects HOME through selectAssistantDestination path', () => {
    const onSelectDestination = vi.fn();
    wrap(
      <QuickActionsBar
        enabled
        authenticated
        onSelectDestination={onSelectDestination}
        onOpenSearch={vi.fn()}
        onParkedCar={vi.fn()}
        onSelectFavouriteParking={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId('qa-home'));
    expect(onSelectDestination).toHaveBeenCalledTimes(1);
    const [dest, origin] = onSelectDestination.mock.calls[0] as [Destination, string];
    expect(dest.label).toBe('Evim');
    expect(origin).toBe('HOME_QUICK_ACTION');
  });

  it('opens search for unconfigured WORK', () => {
    const onOpenSearch = vi.fn();
    wrap(
      <QuickActionsBar
        enabled
        authenticated
        onSelectDestination={vi.fn()}
        onOpenSearch={onOpenSearch}
        onParkedCar={vi.fn()}
        onSelectFavouriteParking={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId('qa-work'));
    expect(onOpenSearch).toHaveBeenCalledTimes(1);
  });
});
