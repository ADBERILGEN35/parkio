import { fireEvent } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { PublicExploreContributeCta } from '../PublicExploreContributeCta';

describe('PublicExploreContributeCta', () => {
  it('hides when not visible', () => {
    const { queryByText } = renderWithProviders(
      <PublicExploreContributeCta visible={false} onPress={jest.fn()} />,
    );
    expect(queryByText('Park yeri bildir')).toBeNull();
  });

  it('shows CTA and support copy and invokes AuthGate handler', () => {
    const onPress = jest.fn();
    const { getByText } = renderWithProviders(
      <PublicExploreContributeCta visible onPress={onPress} />,
    );
    expect(getByText('Park yeri bildir')).toBeTruthy();
    expect(getByText('Topluluğa katkıda bulun')).toBeTruthy();
    fireEvent.press(getByText('Park yeri bildir'));
    expect(onPress).toHaveBeenCalledTimes(1);
  });
});
