import { fireEvent, screen } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { DEFAULT_MUNICIPAL_MAP_FILTERS } from '../municipalFilterModel';
import { MunicipalFilterEntry, MunicipalFilterSheet } from '../MunicipalFilterSheet';
import { MunicipalSummaryBanner } from '../MunicipalSummaryBanner';

describe('MunicipalFilterSheet / entry / summary UI', () => {
  it('renders filter entry with active count', () => {
    const onPress = jest.fn();
    renderWithProviders(
      <MunicipalFilterEntry
        filters={{ ...DEFAULT_MUNICIPAL_MAP_FILTERS, source: 'izum', occupancy: 'live' }}
        onPress={onPress}
      />,
    );
    fireEvent.press(screen.getByLabelText(/Belediye filtreleri, 2 aktif|Municipal filters, 2 active/));
    expect(onPress).toHaveBeenCalled();
  });

  it('exposes source, occupancy, radius, layer, and reset without raw keys', () => {
    const onReset = jest.fn();
    renderWithProviders(
      <MunicipalFilterSheet
        visible
        onClose={jest.fn()}
        filters={{ ...DEFAULT_MUNICIPAL_MAP_FILTERS, source: 'osm', radiusMeters: 500 }}
        onLayerEnabledChange={jest.fn()}
        onSourceChange={jest.fn()}
        onOccupancyChange={jest.fn()}
        onRadiusChange={jest.fn()}
        onReset={onReset}
      />,
    );
    expect(screen.getByText('İzmir Büyükşehir Belediyesi / İZUM')).toBeTruthy();
    expect(screen.getByText('OpenStreetMap')).toBeTruthy();
    expect(screen.queryByText(/izmir-izum/)).toBeNull();
    expect(screen.queryByText(/osm-geofabrik/)).toBeNull();
    expect(screen.getByText(/Canlı doluluk|Live occupancy/)).toBeTruthy();
    expect(screen.getByText('500 m')).toBeTruthy();
    fireEvent.press(screen.getByLabelText(/Filtreleri sıfırla|Reset filters/));
    expect(onReset).toHaveBeenCalled();
  });

  it('renders summary counts and capped total wording', () => {
    renderWithProviders(
      <MunicipalSummaryBanner
        visible
        summary={{ total: 50, live: 1, staticOnly: 49, staleLive: 0 }}
        emptyReason={null}
        resultLimitReached
        resultLimit={50}
      />,
    );
    expect(screen.getByText(/İlk 50|First 50/)).toBeTruthy();
    expect(screen.getByText(/1 canlı|1 with live/)).toBeTruthy();
    expect(screen.getByText(/49 yalnızca|49 with static/)).toBeTruthy();
  });

  it('renders filtered empty with reset', () => {
    const onReset = jest.fn();
    renderWithProviders(
      <MunicipalSummaryBanner
        visible
        summary={{ total: 0, live: 0, staticOnly: 0, staleLive: 0 }}
        emptyReason="filtered"
        resultLimitReached={false}
        resultLimit={50}
        showReset
        onResetFilters={onReset}
      />,
    );
    fireEvent.press(screen.getByRole('button', { name: /Filtreleri sıfırla|Reset filters/ }));
    expect(onReset).toHaveBeenCalled();
  });
});
