import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ParkedCarFocus } from './ParkedCarFocus';

const flyTo = vi.fn();

vi.mock('react-map-gl/maplibre', () => ({
  useMap: () => ({ current: { flyTo } }),
}));

describe('ParkedCarFocus', () => {
  it('flyTo the request coordinates once per token', () => {
    flyTo.mockClear();
    const { rerender } = render(
      <ParkedCarFocus request={{ latitude: 38.42, longitude: 27.14, token: 1 }} />,
    );
    expect(flyTo).toHaveBeenCalledWith(
      expect.objectContaining({
        center: [27.14, 38.42],
        zoom: 16,
      }),
    );

    flyTo.mockClear();
    rerender(<ParkedCarFocus request={{ latitude: 38.42, longitude: 27.14, token: 1 }} />);
    expect(flyTo).not.toHaveBeenCalled();

    rerender(<ParkedCarFocus request={{ latitude: 38.5, longitude: 27.2, token: 2 }} />);
    expect(flyTo).toHaveBeenCalledWith(
      expect.objectContaining({
        center: [27.2, 38.5],
      }),
    );
  });

  it('ignores invalid coordinates', () => {
    flyTo.mockClear();
    render(<ParkedCarFocus request={{ latitude: 999, longitude: 27.14, token: 3 }} />);
    expect(flyTo).not.toHaveBeenCalled();
  });
});