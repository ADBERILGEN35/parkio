import type { PublicSpot } from '@parkio/types';
import type { UseQueryResult } from '@tanstack/react-query';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/utils';
import {
  EMPTY_FILTERS,
  availableSorts,
  availableStatuses,
  defaultSort,
  filterSpots,
  sortSpots,
  withDistance,
  type SpotFilters,
  type SpotSort,
} from '@/lib/spotDiscovery';
import { DiscoveryResults } from './DiscoveryResults';

function makeSpot(overrides: Partial<PublicSpot> & Pick<PublicSpot, 'id'>): PublicSpot {
  return {
    mediaId: 'm',
    latitude: 38.42,
    longitude: 27.14,
    addressText: 'Somewhere',
    description: null,
    manualLocationEdited: false,
    suitableVehicleTypes: ['SEDAN'],
    parkingContext: 'STREET_PARKING',
    legalStatus: 'LEGAL',
    violationReasons: [],
    status: 'ACTIVE',
    expiresAt: '2026-06-20T12:00:00Z',
    createdAt: '2026-06-20T10:00:00Z',
    updatedAt: '2026-06-20T10:00:00Z',
    ...overrides,
  };
}

const center = { lat: 38.42, lng: 27.14 };

// far-old is the *farthest* but the *newest*; near is closest but older + UNCERTAIN
// legal — so nearest vs newest sort, status filter, and legal filter all diverge.
const spots: PublicSpot[] = [
  makeSpot({
    id: 'far',
    addressText: 'Far Street',
    latitude: 38.5,
    longitude: 27.3,
    status: 'FILLED',
    legalStatus: 'LEGAL',
    createdAt: '2026-06-20T11:00:00Z',
  }),
  makeSpot({
    id: 'near',
    addressText: 'Near Avenue',
    latitude: 38.421,
    longitude: 27.141,
    status: 'ACTIVE',
    legalStatus: 'UNCERTAIN',
    createdAt: '2026-06-18T10:00:00Z',
  }),
];

/** Wires DiscoveryResults exactly like MapPage does, to exercise real behavior. */
function Harness() {
  const [filters, setFilters] = useState<SpotFilters>(EMPTY_FILTERS);
  const [sort, setSort] = useState<SpotSort | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const withDist = withDistance(spots, center);
  const statuses = availableStatuses(withDist);
  const sortOptions = availableSorts(true);
  const effectiveSort = sort && sortOptions.includes(sort) ? sort : defaultSort(true);
  const visible = sortSpots(filterSpots(withDist, filters), effectiveSort);

  const search = {
    isPending: false,
    isError: false,
    error: null,
    data: spots,
  } as unknown as UseQueryResult<PublicSpot[], Error>;

  return (
    <div>
      <span data-testid="selected">{selectedId ?? 'none'}</span>
      <DiscoveryResults
        search={search}
        params={{ lat: center.lat, lng: center.lng }}
        spots={visible}
        totalCount={withDist.length}
        filters={filters}
        onFiltersChange={setFilters}
        availableStatuses={statuses}
        sort={effectiveSort}
        onSortChange={setSort}
        sortOptions={sortOptions}
        selectedId={selectedId}
        onSelect={setSelectedId}
        userVehicleType="SEDAN"
      />
    </div>
  );
}

function listAddresses() {
  // Each card has an address link first, then a "View details" CTA link.
  return screen.getAllByRole('listitem').map((li) => within(li).getAllByRole('link')[0].textContent);
}

describe('DiscoveryResults', () => {
  it('shows the result count and a distance chip per spot', () => {
    renderWithProviders(<Harness />);
    expect(screen.getByRole('heading', { name: /2 spots nearby/ })).toBeInTheDocument();
    // Distance chips are derived from real coordinates (no fabricated metrics).
    expect(screen.getAllByText(/^\d+(\.\d+)? (m|km)$/).length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText('Fits your Sedan')).toHaveLength(2);
  });

  it('defaults to nearest sort (closest spot first)', () => {
    renderWithProviders(<Harness />);
    expect(listAddresses()[0]).toBe('Near Avenue');
  });

  it('re-sorts by newest on demand', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    expect(listAddresses()[0]).toBe('Near Avenue'); // nearest default
    await user.selectOptions(screen.getByLabelText('Sort results'), 'newest');
    expect(listAddresses()[0]).toBe('Far Street'); // newest createdAt wins
  });

  it('filters the list by status and reflects the narrowed count', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    expect(screen.getAllByRole('listitem')).toHaveLength(2);

    await user.click(screen.getByRole('button', { name: 'Active', pressed: false }));

    expect(screen.getByRole('heading', { name: /1 of 2 spots/ })).toBeInTheDocument();
    expect(listAddresses()).toEqual(['Near Avenue']);

    // Clearing restores the full list.
    await user.click(screen.getByRole('button', { name: /Clear/ }));
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('shows the no-match state when filters exclude every spot', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    // ACTIVE spot is UNCERTAIN-legal, so status=Active + legal-only yields nothing.
    await user.click(screen.getByRole('button', { name: 'Active' }));
    await user.click(screen.getByRole('button', { name: /Legal only/ }));

    expect(screen.getByText('No spots match these filters')).toBeInTheDocument();
    expect(screen.queryAllByRole('listitem')).toHaveLength(0);
  });

  it('selects a spot and preserves the selection across a re-sort', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(screen.getByRole('button', { name: /Show Near Avenue on map/ }));
    expect(screen.getByTestId('selected')).toHaveTextContent('near');

    // Selection must survive sort changes.
    await user.selectOptions(screen.getByLabelText('Sort results'), 'newest');
    expect(screen.getByTestId('selected')).toHaveTextContent('near');
    expect(screen.getByRole('button', { name: /Show Near Avenue on map/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });
});
