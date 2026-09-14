import { fireEvent } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { PublicExploreSummary } from '../PublicExploreSummary';

describe('PublicExploreSummary municipal copy', () => {
  it('shows explicit municipal visible count (not generic otopark)', () => {
    const { getByText, queryByText } = renderWithProviders(
      <PublicExploreSummary visible loading={false} error={false} visibleCount={5} />,
    );
    expect(getByText('5 belediye otoparkı')).toBeTruthy();
    expect(queryByText(/^5 otopark$/)).toBeNull();
  });

  it('appends pressable +M when municipalHiddenCount > 0', () => {
    const onHidden = jest.fn();
    const { getByText, queryByText } = renderWithProviders(
      <PublicExploreSummary
        visible
        loading={false}
        error={false}
        visibleCount={5}
        municipalHiddenCount={4}
        onMunicipalHiddenPress={onHidden}
      />,
    );
    expect(getByText('5 belediye otoparkı')).toBeTruthy();
    expect(getByText('+4 daha')).toBeTruthy();
    fireEvent.press(getByText('+4 daha'));
    expect(onHidden).toHaveBeenCalledTimes(1);
    expect(queryByText(/\+4 otopark daha/)).toBeNull();
  });

  it('omits +M when municipalHiddenCount is 0', () => {
    const { getByText, queryByText } = renderWithProviders(
      <PublicExploreSummary
        visible
        loading={false}
        error={false}
        visibleCount={3}
        municipalHiddenCount={0}
      />,
    );
    expect(getByText('3 belediye otoparkı')).toBeTruthy();
    expect(queryByText(/\+/)).toBeNull();
  });
});
