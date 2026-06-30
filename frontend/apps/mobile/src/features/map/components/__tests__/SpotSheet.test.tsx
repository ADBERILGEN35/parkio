import { fireEvent } from '@testing-library/react-native';
import { Linking } from 'react-native';
import type { SpotWithDistance } from '@parkio/geo';
import { renderWithProviders } from '@/test/renderWithProviders';
import { SpotSheet } from '../SpotSheet';
import { makeSpot } from '../../__tests__/fixtures';

// @gorhom/bottom-sheet depends on reanimated/gesture-handler native runtimes that
// aren't present under jest; replace it with passthrough views so we can assert on
// the *content* the sheet renders (its imperative open/close is exercised at runtime).
jest.mock('@gorhom/bottom-sheet', () => {
  // `require` (not `import`) is required inside a jest.mock factory: the factory is
  // hoisted above imports, so it can't reference module-scoped bindings.
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const React = require('react');
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { View } = require('react-native');
  const BottomSheet = React.forwardRef(
    ({ children }: { children: React.ReactNode }, ref: React.Ref<unknown>) => {
      React.useImperativeHandle(ref, () => ({ snapToIndex: jest.fn(), close: jest.fn() }));
      return React.createElement(View, null, children);
    },
  );
  BottomSheet.displayName = 'MockBottomSheet';
  function BottomSheetView({ children }: { children: React.ReactNode }) {
    return React.createElement(View, null, children);
  }
  return { __esModule: true, default: BottomSheet, BottomSheetView };
});

jest.mock('../../hooks/useSpotPhoto', () => ({
  useSpotPhoto: () => ({ url: null, isLoading: false }),
}));

function withDistance(spot = makeSpot(), distanceMeters: number | null = 120): SpotWithDistance {
  return { ...spot, distanceMeters };
}

describe('SpotSheet', () => {
  it('renders only real spot facts derived from status (no fabricated values)', () => {
    const spot = withDistance(makeSpot({ status: 'ACTIVE', legalStatus: 'LEGAL' }), 120);
    const { getByText } = renderWithProviders(<SpotSheet spot={spot} onClose={jest.fn()} />);

    expect(getByText('Konak Meydanı, İzmir')).toBeTruthy();
    expect(getByText('Likely available')).toBeTruthy(); // ACTIVE → available
    expect(getByText('Community reported')).toBeTruthy(); // ACTIVE → medium confidence
    expect(getByText('Legal parking')).toBeTruthy();
    expect(getByText(/Updated/)).toBeTruthy();
  });

  it('reflects a filled spot with a danger availability label', () => {
    const spot = withDistance(makeSpot({ status: 'FILLED' }), 80);
    const { getByText } = renderWithProviders(<SpotSheet spot={spot} onClose={jest.fn()} />);
    expect(getByText('Reported filled')).toBeTruthy();
  });

  it('opens platform directions for the spot coordinates', () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(true);
    const spot = withDistance(makeSpot({ latitude: 38.4187, longitude: 27.1283 }), 50);

    const { getByTestId } = renderWithProviders(<SpotSheet spot={spot} onClose={jest.fn()} />);
    fireEvent.press(getByTestId('map.spot.directions'));

    expect(openURL).toHaveBeenCalledTimes(1);
    expect(openURL.mock.calls[0][0]).toContain('38.4187');
    expect(openURL.mock.calls[0][0]).toContain('27.1283');
    openURL.mockRestore();
  });

  it('renders nothing for a null spot', () => {
    const { queryByText } = renderWithProviders(<SpotSheet spot={null} onClose={jest.fn()} />);
    expect(queryByText('Get directions')).toBeNull();
  });
});
