import { fireEvent } from '@testing-library/react-native';
import { Button } from '../Button';
import { renderWithProviders } from '@/test/renderWithProviders';

describe('Button', () => {
  it('renders its label and fires onPress', () => {
    const onPress = jest.fn();
    const { getByRole } = renderWithProviders(<Button label="Sign in" onPress={onPress} />);
    fireEvent.press(getByRole('button', { name: 'Sign in' }));
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('does not fire onPress while loading', () => {
    const onPress = jest.fn();
    const { getByRole } = renderWithProviders(<Button label="Saving" onPress={onPress} loading />);
    fireEvent.press(getByRole('button'));
    expect(onPress).not.toHaveBeenCalled();
  });

  it('exposes a disabled accessibility state', () => {
    const { getByRole } = renderWithProviders(<Button label="Disabled" onPress={jest.fn()} disabled />);
    const button = getByRole('button');
    expect(button.props.accessibilityState.disabled).toBe(true);
  });
});
