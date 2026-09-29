import { fireEvent } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { PublicExploreSummary } from '../PublicExploreSummary';

describe('PublicExploreSummary contextual copy', () => {
  it('A: user-location origin uses near-you municipal copy', () => {
    const { getByText, queryByText } = renderWithProviders(
      <PublicExploreSummary
        visible
        loading={false}
        error={false}
        visibleCount={5}
        origin="user-location"
      />,
    );
    expect(getByText('Konumunuza yakın 5 belediye otoparkı')).toBeTruthy();
    expect(queryByText(/^5 otopark$/)).toBeNull();
    expect(queryByText(/^5 belediye otoparkı$/)).toBeNull();
  });

  it('B/C: area origin (destination or fallback) uses in-area copy', () => {
    const { getByText, queryByText } = renderWithProviders(
      <PublicExploreSummary
        visible
        loading={false}
        error={false}
        visibleCount={5}
        origin="area"
      />,
    );
    expect(getByText('Bu bölgede 5 belediye otoparkı')).toBeTruthy();
    expect(queryByText(/Konumunuza yakın/)).toBeNull();
  });

  it('F/G: community supporting line only when aggregate non-null', () => {
    const onCommunity = jest.fn();
    const { getByText, queryByText, rerender } = renderWithProviders(
      <PublicExploreSummary
        visible
        loading={false}
        error={false}
        visibleCount={3}
        origin="area"
        communitySpotCountInScope={12}
        onCommunityPress={onCommunity}
      />,
    );
    expect(getByText('12 topluluk park noktası da mevcut')).toBeTruthy();
    fireEvent.press(getByText('12 topluluk park noktası da mevcut'));
    expect(onCommunity).toHaveBeenCalledTimes(1);

    rerender(
      <PublicExploreSummary
        visible
        loading={false}
        error={false}
        visibleCount={3}
        origin="area"
        communitySpotCountInScope={null}
        onCommunityPress={onCommunity}
      />,
    );
    expect(queryByText(/topluluk park noktası/)).toBeNull();
    expect(queryByText(/0 topluluk/)).toBeNull();
  });

  it('I/J: hiddenCount > 0 shows pressable +N AuthGate action', () => {
    const onHidden = jest.fn();
    const { getByText, queryByText } = renderWithProviders(
      <PublicExploreSummary
        visible
        loading={false}
        error={false}
        visibleCount={5}
        origin="user-location"
        municipalHiddenCount={3}
        onMunicipalHiddenPress={onHidden}
      />,
    );
    expect(getByText('+3 otopark daha')).toBeTruthy();
    fireEvent.press(getByText('+3 otopark daha'));
    expect(onHidden).toHaveBeenCalledTimes(1);
    expect(queryByText(/\+3 daha$/)).toBeNull();
  });
});
