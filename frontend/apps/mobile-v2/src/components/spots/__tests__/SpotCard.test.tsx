import type { PublicSpot } from '@parkio/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { SpotCard } from '../SpotCard';

function makeSpot(overrides: Partial<PublicSpot> = {}): PublicSpot {
  const now = Date.now();
  return {
    id: 'spot-1',
    mediaId: 'media-1',
    latitude: 38.43,
    longitude: 27.14,
    addressText: 'Kıbrıs Şehitleri yan sokağı, Alsancak',
    description: 'Eczanenin önü az önce boşaldı.',
    manualLocationEdited: false,
    suitableVehicleTypes: ['SEDAN'],
    parkingContext: 'STREET_PARKING',
    legalStatus: 'LEGAL',
    violationReasons: [],
    status: 'VERIFIED',
    createdAt: new Date(now - 3 * 60_000).toISOString(),
    expiresAt: new Date(now + 7 * 60_000).toISOString(),
    updatedAt: new Date(now - 60_000).toISOString(),
    ...overrides,
  };
}

describe('SpotCard', () => {
  it('renders the evidence stack: title, status, freshness, chips', () => {
    const { getByText } = renderWithProviders(
      <SpotCard spot={makeSpot()} photoUri={null} distanceMeters={350} />,
    );
    getByText('Kıbrıs Şehitleri yan sokağı, Alsancak');
    getByText('Doğrulandı'); // status label, TR default
    getByText('Sedan');
    getByText('Cadde üstü');
    getByText('Yasal görünüyor');
    getByText(/3 dk önce paylaşıldı/);
    getByText(/350 m/);
  });

  it('falls back to the context label when no address exists', () => {
    const { getAllByText } = renderWithProviders(
      <SpotCard
        spot={makeSpot({ addressText: null, parkingContext: 'OPEN_PARKING_LOT' })}
        photoUri={null}
      />,
    );
    // Title falls back to context AND the context chip repeats it → 2 matches.
    expect(getAllByText('Açık otopark').length).toBeGreaterThanOrEqual(2);
  });

  it('shows the filled state without a countdown', () => {
    const { getByText, queryByText } = renderWithProviders(
      <SpotCard spot={makeSpot({ status: 'FILLED' })} photoUri={null} />,
    );
    getByText('Doluldu');
    expect(queryByText(/kaldı/)).toBeNull();
  });
});
