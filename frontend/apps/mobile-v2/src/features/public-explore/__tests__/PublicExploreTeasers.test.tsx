import { fireEvent } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { PublicExploreTeasers } from '../PublicExploreTeasers';

describe('PublicExploreTeasers', () => {
  it('hides when not visible', () => {
    const { queryByText } = renderWithProviders(
      <PublicExploreTeasers
        visible={false}
        municipalHiddenCount={4}
        communitySpotCountInScope={12}
        onMunicipalHiddenPress={jest.fn()}
        onCommunityPress={jest.fn()}
      />,
    );
    expect(queryByText(/\+4/)).toBeNull();
  });

  it('shows +N only when municipalHiddenCount > 0 with exact server count', () => {
    const onHidden = jest.fn();
    const { getByText, queryByText, rerender } = renderWithProviders(
      <PublicExploreTeasers
        visible
        municipalHiddenCount={6}
        communitySpotCountInScope={null}
        onMunicipalHiddenPress={onHidden}
        onCommunityPress={jest.fn()}
      />,
    );
    expect(getByText('+6 otopark daha')).toBeTruthy();
    fireEvent.press(getByText('+6 otopark daha'));
    expect(onHidden).toHaveBeenCalledTimes(1);

    rerender(
      <PublicExploreTeasers
        visible
        municipalHiddenCount={0}
        communitySpotCountInScope={null}
        onMunicipalHiddenPress={onHidden}
        onCommunityPress={jest.fn()}
      />,
    );
    expect(queryByText(/otopark daha/)).toBeNull();
  });

  it('shows community teaser only when count is non-null (null ≠ zero)', () => {
    const onCommunity = jest.fn();
    const { getByText, queryByText, rerender } = renderWithProviders(
      <PublicExploreTeasers
        visible
        municipalHiddenCount={0}
        communitySpotCountInScope={12}
        onMunicipalHiddenPress={jest.fn()}
        onCommunityPress={onCommunity}
      />,
    );
    expect(getByText(/12 topluluk park noktası/)).toBeTruthy();
    fireEvent.press(getByText(/12 topluluk park noktası/));
    expect(onCommunity).toHaveBeenCalledTimes(1);

    rerender(
      <PublicExploreTeasers
        visible
        municipalHiddenCount={0}
        communitySpotCountInScope={null}
        onMunicipalHiddenPress={jest.fn()}
        onCommunityPress={onCommunity}
      />,
    );
    expect(queryByText(/topluluk/)).toBeNull();
    expect(queryByText(/0 topluluk/)).toBeNull();
  });
});
