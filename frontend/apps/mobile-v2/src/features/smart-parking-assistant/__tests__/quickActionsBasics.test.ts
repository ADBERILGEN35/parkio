import {
  asAssistantSearchItem,
  destinationFromSavedPlace,
  resolveHomePlace,
} from '@parkio/validation';
import type { SavedPlace } from '@parkio/types';

describe('mobile quick-action destination boundary', () => {
  const home: SavedPlace = {
    id: 'h1',
    kind: 'HOME',
    label: 'Ev',
    latitude: 38.4,
    longitude: 27.1,
    source: 'SYSTEM',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };

  it('resolves HOME and wraps for confirmDestination compatibility', () => {
    expect(resolveHomePlace([home])?.kind).toBe('HOME');
    const dest = destinationFromSavedPlace(home);
    const item = asAssistantSearchItem(dest, 'SAVED_PLACE', { savedPlaceKind: 'HOME' });
    expect(item.destination.label).toBe('Ev');
    expect(item.source).toBe('SAVED_PLACE');
  });
});
