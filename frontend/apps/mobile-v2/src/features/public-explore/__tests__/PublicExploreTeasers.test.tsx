import { fireEvent } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { PublicExploreTeasers } from '../PublicExploreTeasers';

describe('PublicExploreTeasers', () => {
  it('hides when not visible', () => {
    const { queryByText } = renderWithProviders(
      <PublicExploreTeasers
        visible={false}
        communitySpotCountInScope={12}
        onCommunityPress={jest.fn()}
      />,
    );
    expect(queryByText(/topluluk/)).toBeNull();
  });

  it('shows community teaser only when count is non-null (null ≠ zero)', () => {
    const onCommunity = jest.fn();
    const { getByText, queryByText, rerender } = renderWithProviders(
      <PublicExploreTeasers
        visible
        communitySpotCountInScope={12}
        onCommunityPress={onCommunity}
      />,
    );
    expect(getByText(/12 topluluk park noktası/)).toBeTruthy();
    fireEvent.press(getByText(/12 topluluk park noktası/));
    expect(onCommunity).toHaveBeenCalledTimes(1);

    rerender(
      <PublicExploreTeasers
        visible
        communitySpotCountInScope={null}
        onCommunityPress={onCommunity}
      />,
    );
    expect(queryByText(/topluluk/)).toBeNull();
    expect(queryByText(/0 topluluk/)).toBeNull();
  });

  it('does not render municipal +N (compact summary owns that)', () => {
    const { queryByText } = renderWithProviders(
      <PublicExploreTeasers
        visible
        communitySpotCountInScope={null}
        onCommunityPress={jest.fn()}
      />,
    );
    expect(queryByText(/\+/)).toBeNull();
  });
});
